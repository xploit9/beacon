package dev.hermes.android.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionService
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.loginLiveTest
import dev.hermes.android.preferences
import dev.hermes.android.ui.presenceState
import dev.hermes.android.voice.CallTrust
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.normalized
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Lanes assist-overlay, ux-overlay and ux2-overlay: Beacon's own assistant overlay on the emulator, end to end. It makes Beacon the phone's
 * assistant for the run (cmd role, test only), opens the overlay over the home screen, over another app and over the lock screen,
 * asks in writing, listens, starts the live conversation at once (the side button talks, with no tap and no other window: the text box stays and the captions sit above it),
 * ends it with the orb and by closing, checks that over the lock screen the side button talks at once (nothing of it drawn, then a private ask
 * shows the unlock line and the unlock carries the same conversation on) and that the chips fit whole, that a failed send
 * goes back to the pill and a failed call shows its short reason, and takes the
 * review screenshots in four looks (dark, light, Arabic right to left in dark, 1.3x text in light). Everything it changes on the phone (assistant, recognizer, lock screen, dark mode, text size, app language)
 * is put back in `finally`.
 *
 * Opt-in and demo-only: run through device-test.py with `--isolated --grant-mic --arg assistOverlay=1`, which signs the debug app in
 * to the isolated demo Hermes (8446) and puts the owner's debug state back afterwards.
 */
class AssistOverlayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val pkg = app.packageName
    private val assistant = "$pkg/dev.hermes.android.assist.BeaconVoiceInteractionService"
    private val shots by lazy { File(requireNotNull(app.getExternalFilesDir(null)), "assist-overlay-shots").apply { deleteRecursively(); mkdirs() } }
    private val timings = JSONObject()
    private val warmShows = mutableListOf<Long>()

    /** One look the screenshots are taken in. */
    private data class Look(val name: String, val night: Boolean, val fontScale: String, val arabic: Boolean)
    private val looks = listOf(
        Look("dark", night = true, fontScale = "1.0", arabic = false),
        Look("light", night = false, fontScale = "1.0", arabic = false),
        Look("ar-dark", night = true, fontScale = "1.0", arabic = true),
        Look("large-light", night = false, fontScale = "1.3", arabic = false),
    )

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()

    private fun waitFor(what: String, timeoutMillis: Long, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            check(SystemClock.uptimeMillis() < until) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun <T> main(block: () -> T): T { var value: Result<T>? = null; instrumentation.runOnMainSync { value = runCatching(block) }; return value!!.getOrThrow() }

    private fun session(): BeaconAssistSession? = BeaconAssistSession.current?.get()
    private fun controller() = requireNotNull(session()) { "No overlay session" }.controller

    private fun beaconInFront(): Boolean = main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is BeaconActivity } }

    /**
     * The overlay window's own frames over [millis] (FrameMetrics): how many it drew each second, what they cost from the intended
     * vsync to done, how long the render thread took to draw and swap them, and how many missed their deadline. The QA emulator keeps
     * animator durations at 0, which the overlay reads as reduced motion (a still constellation that draws nothing between changes), so
     * motion is switched on for the measurement and the scale put back after it.
     */
    private fun frames(label: String, millis: Long = 3_000) {
        val window = session()?.window?.window ?: return
        val scale = shell("settings get global animator_duration_scale")
        val costs = java.util.concurrent.ConcurrentLinkedQueue<LongArray>()
        val thread = android.os.HandlerThread("assist-frames").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            costs += longArrayOf(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION), metrics.getMetric(android.view.FrameMetrics.DEADLINE),
                metrics.getMetric(android.view.FrameMetrics.SYNC_DURATION) + metrics.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) +
                    metrics.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION))
        }
        val elapsed: Long
        try {
            shell("settings put global animator_duration_scale 1")
            Thread.sleep(800)
            main { window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(thread.looper)) }
            val start = SystemClock.uptimeMillis()
            Thread.sleep(millis)
            main { window.removeOnFrameMetricsAvailableListener(listener) }
            elapsed = SystemClock.uptimeMillis() - start
        } finally {
            shell("settings put global animator_duration_scale ${scale.ifBlank { "1" }.takeIf { it != "null" } ?: "1"}")
            thread.quitSafely()
        }
        val all = costs.toList()
        val ms = all.map { it[0] / 1e6 }.sorted()
        val render = all.map { it[2] / 1e6 }.sorted()
        fun pct(values: List<Double>, p: Double) = if (values.isEmpty()) 0.0 else Math.round(values[((values.size - 1) * p).toInt()] * 10) / 10.0
        frameTimes.put(label, JSONObject().put("fps", Math.round(ms.size * 10_000.0 / elapsed) / 10.0).put("frames", ms.size).put("seconds", elapsed / 1000.0)
            .put("p50_ms", pct(ms, .5)).put("p90_ms", pct(ms, .9)).put("p99_ms", pct(ms, .99)).put("render_p50_ms", pct(render, .5))
            .put("missed_deadline", all.count { it[1] > 0 && it[0] > it[1] })
            .put("mood", dustMood(controller().state).name))
        Thread.sleep(400)
    }
    private val frameTimes = JSONObject()

    /** Every semantics node the overlay window holds now, unmerged; call on the main thread (see [onNodes]). */
    private fun collectNodes(): List<SemanticsNode> {
        val decor = session()?.window?.window?.decorView ?: return emptyList()
        fun views(v: android.view.View): Sequence<android.view.View> =
            sequenceOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).asSequence().flatMap { views(g.getChildAt(it)) } } ?: emptySequence())
        fun all(n: SemanticsNode): Sequence<SemanticsNode> = sequenceOf(n) + n.children.asSequence().flatMap { all(it) }
        return views(decor).filterIsInstance<androidx.compose.ui.platform.ViewRootForTest>().flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }.toList()
    }

    /** Reads the overlay's semantics on the main thread, where Compose keeps them. */
    private fun <T> onNodes(read: (List<SemanticsNode>) -> T): T = main { read(collectNodes()) }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun hasTag(tag: String): Boolean = onNodes { all -> all.any { it.tag() == tag && it.layoutInfo.isPlaced } }

    /** The words the overlay shows now: every text and field value on screen. */
    private fun shownTexts(): List<String> = onNodes { all -> all.flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
    } }

    /** Clicks the overlay control tagged [tag] through Compose's own semantics in this process; false when it is not on screen. */
    private fun clickTag(tag: String): Boolean = onNodes { all ->
        all.firstOrNull { it.tag() == tag }?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action?.invoke() == true
    }

    /** Puts the overlay in [change]'s state, for the stills of what is rare to reach on demand (a send that failed, a call that didn't start). */
    private fun stage(change: (AssistOverlayState) -> AssistOverlayState) = main {
        val c = controller()
        AssistOverlayController::class.java.getDeclaredMethod("setState", AssistOverlayState::class.java).apply { isAccessible = true }.invoke(c, change(c.state))
    }

    /**
     * Waits for the main thread to go quiet, but never for good: with a live conversation up the dust animates and the instrumentation's
     * idle wait can go on forever (it stopped a whole run twice), so a screenshot takes the screen as it is after [millis].
     */
    private fun waitIdle(millis: Long = 3_000) {
        val waiter = Thread { runCatching { instrumentation.waitForIdleSync() } }.apply { isDaemon = true; start() }
        waiter.join(millis)
    }

    private fun capture(name: String, settle: Long = 700) {
        waitIdle()
        Thread.sleep(settle)
        val image = requireNotNull(automation.takeScreenshot()) { "No screenshot" }
        File(shots, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** Opens the overlay the way the system does and waits for its first dust frame. Returns that frame's time from onShow. */
    private fun summon(): Long {
        val before = session()?.controller?.firstFrames?.size ?: 0
        // The assist key is the phone's own gesture path. `cmd voiceinteraction show` needs a permission the shell lacks on
        // Android 16, so it is only the fallback for builds that do not route the key.
        shell("input keyevent KEYCODE_ASSIST")
        val shown = runCatching { waitFor("the overlay", 8_000) { (session()?.controller?.firstFrames?.size ?: 0) > before } }.isSuccess
        if (!shown) {
            shell("cmd voiceinteraction show")
            waitFor("the overlay after cmd voiceinteraction show", 10_000) { (session()?.controller?.firstFrames?.size ?: 0) > before }
        }
        waitFor("the summon to finish", 5_000) { controller().state.phase == OverlayPhase.Open }
        return controller().firstFrames.last()
    }

    /** Beacon's app language before the run; empty when the owner chose none. */
    private var appLocales = ""

    private fun restoreAppLocale() {
        // executeShellCommand passes no shell quoting, so an "empty" --locales argument would become the locale "und".
        shell(if (appLocales.isEmpty()) "cmd locale set-app-locales $pkg" else "cmd locale set-app-locales $pkg --locales ${appLocales.replace(" ", "")}")
    }

    /** Switches the phone to [look]; the overlay reads its language on the next summon, its theme and text size at once. */
    private fun wear(look: Look) {
        shell("cmd uimode night ${if (look.night) "yes" else "no"}")
        shell("settings put system font_scale ${look.fontScale}")
        if (look.arabic) shell("cmd locale set-app-locales $pkg --locales ar") else restoreAppLocale()
        Thread.sleep(2_000)
    }

    private fun dismissWithBack() {
        shell("input keyevent KEYCODE_BACK")
        waitFor("the overlay to close", 6_000) { controller().state.phase == OverlayPhase.Hidden }
    }

    /** [waitFor] whose failure message carries what [detail] says when the wait ends, not when it began. */
    private fun waitForDetail(what: String, timeoutMillis: Long, detail: () -> String, condition: () -> Boolean) {
        try { waitFor(what, timeoutMillis, condition) } catch (error: IllegalStateException) { throw IllegalStateException("${error.message}: ${detail()}", error) }
    }

    /** The app's own recent warnings and errors, for a failure message: what Android and the app logged while the overlay waited. */
    private fun logTail(): String = shell("logcat -d -t 3000 -v brief *:W").lines()
        .filter { line -> listOf("dev.hermes", "Beacon", "ForegroundService", "AndroidRuntime", "VoiceInteraction").any { line.contains(it) } }.takeLast(14).joinToString(" | ") { it.take(220) }

    /** What the overlay and the assistant chat hold, for a failure message. */
    private fun overlayState(): String {
        val c = session()?.controller ?: return "no session"
        val vm = c.vm
        val ui = vm?.ui?.value
        val chat = vm?.chat?.value
        return "overlay=${c.state.copy(ask = c.state.ask?.copy(answer = c.state.ask?.answer?.take(80).orEmpty()))} " +
            "ui=[auth=${ui?.authenticated} restoring=${ui?.restoringSession} loading=${ui?.loading} connected=${ui?.connected} tab=${ui?.tab} opening=${ui?.beaconOpening} beaconError=${ui?.beaconError} notice=${ui?.notice} error=${ui?.error}] " +
            "call=[active=${app.calls.state.value.active} state=${app.calls.state.value.state} error=${app.calls.state.value.error.take(80)} reason=${app.calls.state.value.failureReason.take(80)}] " +
            "chat=[session=${chat?.sessionId?.take(8)} stream=${chat?.streamId?.take(8)} uncertain=${chat?.uncertain?.take(8)} status=${chat?.status} messages=${chat?.messages?.size} " +
            "last=${chat?.messages?.takeLast(3)?.map { it.role + ":" + it.text.take(30) }} live=${chat?.liveText?.take(40)} tools=${chat?.liveTools?.size} approval=${chat?.approval != null} clarification=${chat?.clarification != null} queued=${chat?.queued?.size}]"
    }

    /** Denies a tool approval waiting in the demo agent's chat; the questions here never need its tools. True if one was waiting. */
    private fun denyPendingApproval(): Boolean {
        val vm = controller().vm ?: return false
        val pending = vm.chat.value.approval ?: return false
        val scope = vm.authenticatedScope() ?: return false
        val choices = pending.optJSONArray("choices")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
        if ("deny" !in choices) return false
        main { vm.approval("deny", pending, scope) }
        return true
    }

    private fun askAndWait(question: String) {
        // An approval left waiting (an earlier run, or the agent choosing a tool) holds every send in that chat.
        if (denyPendingApproval()) waitFor("the earlier approval to clear", 90_000) { controller().vm?.chat?.value?.let { it.approval == null && it.streamId.isBlank() } == true }
        main { controller().draft = TextFieldValue(question); controller().ask() }
        val done = runCatching {
            waitFor("the written answer", 180_000) {
                // The overlay says honestly that something waits; the test captures that once, then denies the tool and lets the reply finish.
                if (controller().state.line == OverlayLine.Waiting && controller().vm?.chat?.value?.approval != null) {
                    if (!timings.has("approval_waiting_line")) { capture("waiting-approval"); timings.put("approval_waiting_line", true) }
                    denyPendingApproval(); Thread.sleep(1_000)
                }
                controller().state.ask?.let { !it.streaming && (it.answer.isNotBlank() || it.failed) } == true
            }
        }
        if (done.isFailure) { capture("fail-ask"); timings.put("fail_ask", overlayState()); throw AssertionError("No written answer: ${overlayState()}") }
        val ask = requireNotNull(controller().state.ask)
        assertFalse("The question was not sent: ${controller().state.line}", ask.failed)
        assertEquals(OverlaySurface.Answer, controller().state.surface)
    }

    /**
     * Over an app, once the assistant chat has opened: the chips that show sit whole on screen, none cut at either edge, in every
     * look (the fit is measured against the real screen, not the clipped bounds a cut chip would report).
     */
    private fun assertChipsFit(look: Look) {
        runCatching { waitFor("the chips", 20_000) { hasTag("assist-chip-0") } }
        Thread.sleep(800)
        val width = main { requireNotNull(session()?.window?.window?.decorView).width }
        val chips = onNodes { all ->
            all.filter { it.tag()?.startsWith("assist-chip-") == true && it.layoutInfo.isPlaced }.map { Triple(it.tag()!!, it.positionInRoot.x, it.positionInRoot.x + it.size.width) }
        }
        timings.put("chips_${look.name}", JSONArray(chips.map { (tag, left, right) -> "$tag ${left.toInt()}..${right.toInt()} of $width" }))
        assertTrue("No chips over Settings in ${look.name}: ${overlayState()}", chips.isNotEmpty())
        chips.forEach { (tag, left, right) -> assertTrue("$tag is cut at the edge in ${look.name}: $left..$right of $width", left >= 0f && right <= width) }
        assertFalse("Talking live is the orb's job, never a chip", shownTexts().any { it == "Talk to Beacon" })
    }

    /** [english] as the overlay shows it in [look]: the catalog's Arabic in the Arabic look, the text itself otherwise. */
    private fun shownIn(look: Look, english: String): String =
        if (look.arabic) app.readTranslations().getJSONObject("ar").getString(normalized(english)) else english

    /** Waits until the overlay shows exactly [text]. */
    private fun waitForText(what: String, text: String) = waitFor(what, 8_000) { shownTexts().any { it == text } }

    /** The bounds of the nodes tagged [tags], each as left, right, top, bottom in screen pixels, read in one pass on the main thread. */
    private fun boundsOf(vararg tags: String): List<FloatArray> = onNodes { all ->
        tags.map { tag ->
            val n = all.first { it.tag() == tag && it.layoutInfo.isPlaced }
            floatArrayOf(n.positionInRoot.x, n.positionInRoot.x + n.size.width, n.positionInRoot.y, n.positionInRoot.y + n.size.height)
        }
    }

    /**
     * The side button talks at once: waits for the conversation to start by itself, checks it is the overlay's alone (the app's call
     * window held away, the app not in front, the text box still there, no band and no "Open call"), then ends it with the orb so the
     * written-answer flows that follow run on the bare pill.
     */
    private fun endAutoLive() {
        waitFor("the conversation to start by itself", 30_000) { controller().state.surface == OverlaySurface.Live || controller().state.line != null }
        if (controller().state.surface != OverlaySurface.Live) return
        assertTrue("The overlay holds the app's call window away while it shows", app.calls.uiVisible)
        assertFalse("The conversation must not bring the app to the front", beaconInFront())
        assertTrue("The text box stays during the conversation", hasTag("assist-pill") && hasTag("assist-field") && hasTag("assist-live"))
        assertFalse("No band of its own", hasTag("assist-live-band"))
        assertFalse("No Open call panel", hasTag("assist-live-open"))
        main { controller().endCall() }
        waitFor("the conversation to end", 45_000) { controller().state.surface != OverlaySurface.Live && !app.calls.state.value.active }
    }
    /**
     * Section 6 of the full run, and the whole of [theSideButtonTalksOnTheLockScreenAndTheUnlockCarriesOn]: the overlay over the lock screen
     * in every look, then a private ask and the unlock. Leaves the phone unlocked (the caller restores the lock setting).
     */
    private fun overTheLockScreen() {
    // 6. Over the lock screen in every look: the side button talks at once (owner decision, 4 Nov, "talk now, unlock for private").
    // The conversation starts by itself, untrusted: no unlock line until something private is asked for, and nothing of it is drawn
    // over the lock screen (no words, no chip, no mic, no send); the lock-screen look of the overlay is otherwise the same.
    shell("locksettings set-disabled false")
    shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1_500); shell("input keyevent KEYCODE_WAKEUP")
    val keyguard = requireNotNull(app.getSystemService(KeyguardManager::class.java))
    waitFor("the lock screen", 10_000) { keyguard.isKeyguardLocked }
    val power = requireNotNull(app.getSystemService(android.os.PowerManager::class.java))
    for (look in looks) {
        wear(look)
        // The lock screen sleeps ten seconds after it wakes, and the system hides the overlay with the screen. The open overlay
        // holds the screen for half a minute after the last interaction, so each look wakes the phone afresh.
        shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1_000)
        shell("input keyevent KEYCODE_WAKEUP"); Thread.sleep(500)
        summon()
        assertTrue("The overlay knows it is over the lock screen", controller().locked)
        assertTrue(keyguard.isKeyguardLocked)
        val summoned = SystemClock.uptimeMillis()
        waitFor("the conversation to start at once over the lock screen in ${look.name}: ${overlayState()} log=${logTail()}", 60_000) {
            (app.calls.state.value.active && controller().state.surface == OverlaySurface.Live) || controller().state.line == OverlayLine.CallFailed
        }
        assertTrue("The locked start failed in ${look.name}: ${overlayState()} log=${logTail()}", app.calls.state.value.active && controller().state.surface == OverlaySurface.Live)
        timings.put("locked_start_ms_${look.name}", SystemClock.uptimeMillis() - summoned)
        assertNull("No unlock line before anything private is asked for in ${look.name}: ${overlayState()}", controller().state.line)
        assertTrue("The phone is still locked while it talks", keyguard.isKeyguardLocked)
        assertFalse("No chip over the lock screen", hasTag("assist-chip-0"))
        assertFalse("No mic over the lock screen", hasTag("assist-mic"))
        assertFalse("No send over the lock screen", hasTag("assist-send"))
        assertFalse("The conversation's words are not drawn over the lock screen", hasTag("assist-captions"))
        assertNull(controller().state.ask)
        assertTrue("The screen stays on over the lock screen while it talks", power.isInteractive && controller().state.phase == OverlayPhase.Open)
        capture("lock-talk-${look.name}")
        if (look.name == "dark") frames("locked")
        dismissWithBack()
        waitFor("the locked conversation to end with the overlay", 45_000) { !app.calls.state.value.active }
    }
    // Something private asked for over the lock screen: the model tries its tool, which is refused, and the overlay says to unlock.
    // Unlock asks Android (no PIN here, so it succeeds at once) and the same conversation carries on, trusted.
    wear(looks[0])
    shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1_000)
    shell("input keyevent KEYCODE_WAKEUP"); Thread.sleep(500)
    summon()
    // The call has its id once Hermes' admission lands, a moment after it turns active: the id is what says it is the same call later.
    waitFor("the locked conversation: ${overlayState()}", 45_000) { app.calls.state.value.active && controller().state.surface == OverlaySurface.Live && app.calls.state.value.callId.isNotBlank() }
    val lockedCall = app.calls.state.value.callId
    var asked = 0
    val fromTheModel = runCatching {
        waitFor("the unlock line after a private ask", 45_000) {
            if (controller().state.line == OverlayLine.Unlock) true
            else {
                // A typed turn into the conversation: the model's own words decide whether it tries a tool, so ask again now and then.
                if (asked == 0 || SystemClock.uptimeMillis() % 15_000 < 600) {
                    asked++
                    main { controller().draft = TextFieldValue("Please check what you remember about me with your memory tool, and tell me one thing."); controller().ask() }
                    Thread.sleep(700)
                }
                false
            }
        }
    }.isSuccess
    if (!fromTheModel) {
        // The model answered without trying its tool this time. The refusal is what puts the line up (the count the overlay watches is
        // process-wide), so one stands in for it; the model's half is pinned by LockedStartCallTest.
        CallTrust { true }.refused()
        waitForDetail("the unlock line after a refusal", 10_000, { overlayState() }) { controller().state.line == OverlayLine.Unlock }
    }
    timings.put("private_asks_before_unlock_line", asked)
    timings.put("unlock_line_from_the_model", fromTheModel)
    waitFor("its Unlock action", 8_000) { hasTag("assist-line-action") }
    waitForText("the unlock line", shownIn(looks[0], "Unlock your phone first."))
    assertTrue("The conversation is still up under the line", app.calls.state.value.active && controller().state.surface == OverlaySurface.Live)
    capture("lock-private-asks-unlock")
    assertTrue("Unlock asks Android for the unlock", clickTag("assist-line-action"))
    waitFor("the phone to unlock", 20_000) { !keyguard.isKeyguardLocked }
    waitForDetail("the overlay back with its conversation after the unlock", 30_000, { "${overlayState()} locked=${controller().locked} keyguard=${keyguard.isKeyguardLocked} interactive=${power.isInteractive} log=${logTail()}" }) {
        controller().state.surface == OverlaySurface.Live && controller().state.line == null && !controller().locked
    }
    assertEquals("The same conversation carries on after the unlock", lockedCall, app.calls.state.value.callId)
    waitForDetail("the conversation to listen again, trusted", 60_000, { "${overlayState()} log=${logTail()}" }) { app.calls.state.value.active && app.calls.state.value.state == "listening" }
    assertEquals(lockedCall, app.calls.state.value.callId)
    capture("unlock-carries-on")
    dismissWithBack()
    waitFor("the conversation to end with the overlay", 45_000) { !app.calls.state.value.active }
    }

    /** Non-intrusive, always runs: Android can find the assistant service, its session and the pass-through recognizer. */
    @Test fun androidSeesBeaconAsAnAssistantWithARecognizer() {
        val pm = app.packageManager
        val services = pm.queryIntentServices(Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(pkg), PackageManager.GET_META_DATA)
        val vis = services.single().serviceInfo
        assertEquals(BeaconVoiceInteractionService::class.java.name, vis.name)
        assertEquals(android.Manifest.permission.BIND_VOICE_INTERACTION, vis.permission)
        assertNotNull(vis.metaData?.getInt("android.voice_interaction"))
        val recognizer = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(pkg), 0).single().serviceInfo
        assertEquals(BeaconRecognitionService::class.java.name, recognizer.name)
        assertTrue(recognizer.exported)
        // The fallback for phones without a voice interaction service is still the assist activity.
        assertEquals(AssistEntryActivity::class.java.name, pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY).single().activityInfo.name)
    }

    /**
     * Lane LS on its own: over the lock screen the side button starts the conversation at once (untrusted: nothing of it drawn), a private
     * ask shows the unlock line, and the unlock carries the same conversation on. The same opt-in and the same demo Hermes as the full run;
     * everything it changes on the phone is put back.
     */
    @Test(timeout = 900_000) fun theSideButtonTalksOnTheLockScreenAndTheUnlockCarriesOn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: --arg assistOverlay=1 with --isolated", args.getString("assistOverlay") == "1")
        val auth = File(app.filesDir, "live-auth.json")
        assumeTrue("Needs the isolated demo sign-in (device-test.py --isolated)", auth.isFile)
        val credentials = JSONObject(auth.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val origin = credentials.getString("origin")
        require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "The overlay test only runs against the demo Hermes" }
        runBlocking {
            loginLiveTest(app, origin, credentials)
            app.preferences.edit { it[stringPreferencesKey("origin")] = origin; it[booleanPreferencesKey("setup_complete")] = true; it[booleanPreferencesKey("setup_active")] = false }
        }
        val holders = shell("cmd role get-role-holders android.app.role.ASSISTANT").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val recognizer = shell("settings get secure voice_recognition_service")
        val lockDisabled = shell("locksettings get-disabled")
        val night = shell("cmd uimode night")
        val fontScale = shell("settings get system font_scale")
        appLocales = Regex("""\[(.*)]""").find(shell("cmd locale get-app-locales $pkg"))?.groupValues?.get(1).orEmpty().trim()
        try {
            shell("cmd role add-role-holder android.app.role.ASSISTANT $pkg")
            waitFor("Beacon to become the assistant", 20_000) { shell("settings get secure voice_interaction_service") == assistant }
            wear(looks[0])
            // The first show on a cold process restores the sign-in and opens the assistant chat, which takes a while on this emulator
            // (the full run waits as long): warm the overlay up unlocked first, as that run's section 1 does, then go to the lock screen.
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1_500)
            summon()
            endAutoLive()
            waitFor("the assistant chat to open in the overlay: ${overlayState()}", 150_000) {
                val vm = controller().vm
                vm != null && vm.ui.value.authenticated && vm.chat.value.sessionId.isNotBlank() && vm.isBeaconConversation()
            }
            dismissWithBack()
            overTheLockScreen()
            shell("wm dismiss-keyguard")
        } finally {
            runCatching { app.calls.hangup() }
            runCatching { session()?.let { s -> main { s.hide() } } }
            shell("settings put system font_scale $fontScale")
            restoreAppLocale()
            shell(if (night.contains("yes")) "cmd uimode night yes" else if (night.contains("no")) "cmd uimode night no" else "cmd uimode night auto")
            if (lockDisabled == "true") { shell("wm dismiss-keyguard"); shell("locksettings set-disabled true") }
            if (holders.isEmpty()) shell("cmd role clear-role-holders android.app.role.ASSISTANT")
            else shell("cmd role add-role-holder android.app.role.ASSISTANT ${holders.first()}")
            if (recognizer.isNotBlank() && recognizer != "null" && shell("settings get secure voice_recognition_service") != recognizer) shell("settings put secure voice_recognition_service $recognizer")
            File(shots, "timings.json").writeText(timings.toString(2))
        }
        assertEquals(holders, shell("cmd role get-role-holders android.app.role.ASSISTANT").lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test(timeout = 1_500_000) fun theOverlayOpensOverAppsAndTheLockScreenWithoutTheApp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: --arg assistOverlay=1 with --isolated", args.getString("assistOverlay") == "1")
        val auth = File(app.filesDir, "live-auth.json")
        assumeTrue("Needs the isolated demo sign-in (device-test.py --isolated)", auth.isFile)
        val credentials = JSONObject(auth.readText(Charsets.UTF_8).removePrefix("﻿"))
        val origin = credentials.getString("origin")
        // Only the isolated demo Hermes (served on 8446) is ever signed in to.
        require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "The overlay test only runs against the demo Hermes" }
        runBlocking {
            loginLiveTest(app, origin, credentials)
            app.preferences.edit { it[stringPreferencesKey("origin")] = origin; it[booleanPreferencesKey("setup_complete")] = true; it[booleanPreferencesKey("setup_active")] = false }
        }

        val holders = shell("cmd role get-role-holders android.app.role.ASSISTANT").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val recognizer = shell("settings get secure voice_recognition_service")
        val lockDisabled = shell("locksettings get-disabled")
        val night = shell("cmd uimode night")
        val fontScale = shell("settings get system font_scale")
        appLocales = Regex("""\[(.*)]""").find(shell("cmd locale get-app-locales $pkg"))?.groupValues?.get(1).orEmpty().trim()
        try {
            shell("cmd role add-role-holder android.app.role.ASSISTANT $pkg")
            waitFor("Beacon to become the assistant", 20_000) { shell("settings get secure voice_interaction_service") == assistant }
            assertEquals(PhoneAssistantState.Active, phoneAssistantState(app))

            // 1. Over the home screen: the cold first show opens the pill and starts the conversation after its first frame.
            wear(looks[0])
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1_500)
            timings.put("cold_first_frame_ms", summon())
            assertFalse("The overlay must not bring the app to the front", beaconInFront())
            assertFalse("The first summon shows no hint any more", hasTag("assist-talk-hint"))
            endAutoLive()
            waitFor("the assistant chat to open in the overlay", 90_000) {
                val vm = controller().vm
                vm != null && vm.ui.value.authenticated && vm.chat.value.sessionId.isNotBlank() && vm.isBeaconConversation()
            }
            capture("01-home-dark")
            dismissWithBack()

            // 2. Over another app, warm: the window and the first dust frame within 100 ms of onShow.
            shell("am start -W -a android.settings.SETTINGS")
            Thread.sleep(1_500)
            // Back to back, the next gesture lands while the last close is still settling; that is recorded, not judged.
            val backToBack = (1..2).map { summon().also { dismissWithBack() } }
            timings.put("back_to_back_first_frame_ms", JSONArray(backToBack))
            // A person's next gesture comes a moment later: that is the warm show the 100 ms bar is about.
            // Seven, so the median reflects the overlay rather than the software renderer's jitter (one run spans 87-182 ms).
            val warm = (1..7).map { Thread.sleep(1_500); summon().also { dismissWithBack() } }
            timings.put("warm_first_frame_ms", JSONArray(warm))
            Thread.sleep(1_500)
            summon()
            val top = shell("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }.orEmpty()
            assertTrue("Settings stays in front under the overlay: $top", top.contains("com.android.settings"))
            assertFalse(beaconInFront())
            // Measured after the warm shows: switching motion on and off for a measurement touches every process on the phone.
            frames("idle-settings-dark")
            warmShows += warm
            dismissWithBack()

            wear(looks[0])
            Thread.sleep(1_500)
            // 3. Over an app in every look: the bare pill and its chips, a written answer, a send that failed, a call that didn't start.
            for (look in looks) {
                wear(look)
                summon()
                if (look.arabic) assertEquals("ar", controller().locale.language)
                endAutoLive()
                assertChipsFit(look)
                capture("app-idle-${look.name}")
                askAndWait("Without using any tools, reply in one short sentence: say hello.")
                assertFalse(beaconInFront())
                capture("app-answer-${look.name}")
                if (look.name == "dark") frames("answer-dark")
                // "Couldn't send": back to the pill with the question in the field, and Try again on the line.
                val question = "What's on my calendar today?"
                // Closing the card first lets go of the answered question, so its chat can't update the staged state.
                main { controller().closeCard() }
                stage { reduce(reduce(it, OverlayEvent.Asked(question, false, "")), OverlayEvent.NotSent) }
                main { controller().draft = TextFieldValue(question) }
                waitFor("Couldn't send with Try again", 5_000) { hasTag("assist-line-action") }
                waitForText("the NotSent line", shownIn(look, "Couldn't send"))
                waitForText("Try again", shownIn(look, "Try again"))
                assertEquals("A failed send goes back to the pill", OverlaySurface.Pill, controller().state.surface)
                assertFalse("No empty card repeats the question in ${look.name}", hasTag("assist-answer"))
                assertTrue("The question is back in the field in ${look.name}", shownTexts().any { it == question })
                capture("app-notsent-${look.name}")
                // A call that didn't start: the short reason beside Call again, without a second instruction.
                main { controller().closeCard() }
                stage { reduce(it, OverlayEvent.CallFailed(CallReason.HermesNoAnswer)) }
                main { controller().draft = TextFieldValue() }
                waitFor("the failed call's line with Call again", 5_000) { hasTag("assist-line-action") && shownTexts().none { it == question } }
                waitForText("Hermes' short reason", shownIn(look, "Hermes didn't answer"))
                waitForText("Call again", shownIn(look, "Call again"))
                assertTrue("The reason has no Try again of its own in ${look.name}", shownTexts().none { it.contains(shownIn(look, "Try again")) })
                capture("app-call-failure-${look.name}")
                // An expired sign-in: its action is Sign in, which opens Beacon unlocked, not Call again.
                stage { reduce(it, OverlayEvent.CallFailed(CallReason.SignInExpired)) }
                waitForText("the sign-in reason", shownIn(look, "Your sign-in expired"))
                waitForText("Sign in", shownIn(look, "Sign in"))
                assertTrue("An expired sign-in does not offer Call again in ${look.name}", shownTexts().none { it == shownIn(look, "Call again") })
                capture("app-call-failure-signin-${look.name}")
                dismissWithBack()
            }
            wear(looks[0])

            // 4. The mic: dictation listens here and answers in writing.
            summon()
            endAutoLive()
            main { controller().dictate() }
            waitFor("dictation to start", 5_000) { controller().state.dictating || controller().state.line != null }
            if (controller().state.dictating) { capture("07-listening"); frames("listening", 2_000) }
            timings.put("dictation", if (controller().state.dictating) "listening" else controller().state.line.toString())
            main { if (controller().state.dictating) controller().dictate() }
            Thread.sleep(1_000)

            // 5. The side button talks at once, in the overlay alone: the conversation starts by itself (no tap), the app stays in the
            // background, the text box stays with the captions above it, and there is no band, no Open call and no call screen.
            summon()
            waitFor("the live conversation to start", 45_000) { app.calls.state.value.active && app.calls.state.value.state != "idle" }
            assertEquals(OverlaySurface.Live, controller().state.surface)
            assertFalse("A conversation from the overlay must not open the app", beaconInFront())
            assertTrue("The overlay holds the app's call window away", app.calls.uiVisible)
            assertTrue("The text box stays during the conversation", hasTag("assist-pill") && hasTag("assist-field") && hasTag("assist-live"))
            assertFalse("No band of its own", hasTag("assist-live-band"))
            assertFalse("No Open call panel", hasTag("assist-live-open"))
            timings.put("call_state", app.calls.state.value.state)
            capture("app-live-dark")
            val spoke = runCatching { waitFor("the assistant to speak", 25_000) { presenceState(app.calls.state.value) == SphereState.Speaking } }.isSuccess
            timings.put("speaking_seen", spoke)
            if (spoke) { capture("09-speaking"); frames("speaking", 2_000) }
            // After the greeting, so measuring never hides it: the live frames, then a typed turn from the text box, whose words show
            // in the captions directly above it.
            frames("live")
            if (app.calls.state.value.active) {
                main { controller().draft = TextFieldValue("Say OK."); controller().ask() }
                runCatching { waitFor("the typed turn in the captions", 10_000) { hasTag("assist-captions") } }
                val captioned = hasTag("assist-captions")
                timings.put("typing_still", captioned)
                if (captioned) {
                    val (captions, pill) = boundsOf("assist-captions", "assist-pill")
                    assertTrue("The captions sit above the text box: ${captions.toList()} over ${pill.toList()}", captions[3] <= pill[2] + 1f)
                    capture("08b-live-typing")
                }
            } else timings.put("typing_still", false)
            // Each look needs the overlay opened again (the language is read on a summon), and closing ends the conversation.
            for (look in looks.drop(1)) {
                if (!app.calls.state.value.active) break
                dismissWithBack()
                waitFor("the conversation to end with the overlay", 45_000) { !app.calls.state.value.active }
                wear(look)
                summon()
                if (look.arabic) assertEquals("ar", controller().locale.language)
                waitFor("the live conversation in ${look.name}", 45_000) { app.calls.state.value.active && controller().state.surface == OverlaySurface.Live }
                Thread.sleep(1_500)
                capture("app-live-${look.name}")
            }
            assertFalse(beaconInFront())
            // The orb is the one control: a tap on it ends the conversation and the pill is the talk button again.
            assertTrue("The orb ends the conversation", clickTag("assist-live"))
            waitFor("the conversation to end with the orb", 45_000) { !app.calls.state.value.active && controller().state.surface != OverlaySurface.Live }
            // A tap to talk starts it again; closing the overlay then ends it, so nothing keeps listening hidden.
            assertTrue("The orb talks again", clickTag("assist-live"))
            waitFor("the conversation to start again", 45_000) { app.calls.state.value.active }
            dismissWithBack()
            waitFor("the conversation to end with the overlay", 45_000) { !app.calls.state.value.active }
            assertFalse("Nothing holds the app's call window once the overlay is closed", app.calls.uiVisible)
            wear(looks[0])
            overTheLockScreen()
            shell("wm dismiss-keyguard")
        } finally {
            runCatching { app.calls.hangup() }
            runCatching { session()?.let { s -> main { s.hide() } } }
            shell("settings put system font_scale $fontScale")
            restoreAppLocale()
            shell(if (night.contains("yes")) "cmd uimode night yes" else if (night.contains("no")) "cmd uimode night no" else "cmd uimode night auto")
            if (lockDisabled == "true") { shell("wm dismiss-keyguard"); shell("locksettings set-disabled true") }
            if (holders.isEmpty()) shell("cmd role clear-role-holders android.app.role.ASSISTANT")
            else shell("cmd role add-role-holder android.app.role.ASSISTANT ${holders.first()}")
            if (recognizer.isNotBlank() && recognizer != "null" && shell("settings get secure voice_recognition_service") != recognizer) shell("settings put secure voice_recognition_service $recognizer")
            timings.put("frames", frameTimes)
            File(shots, "timings.json").writeText(timings.toString(2))
        }
        assertEquals(holders, shell("cmd role get-role-holders android.app.role.ASSISTANT").lines().map { it.trim() }.filter { it.isNotEmpty() })
        assertEquals("Beacon's app language is put back", appLocales, Regex("""\[(.*)]""").find(shell("cmd locale get-app-locales $pkg"))?.groupValues?.get(1).orEmpty().trim())
        // The design target is a first dust frame within 100 ms of the gesture on a phone. This emulator renders in software
        // (lavapipe): on a quiet host the build before the 1.1.23 overlay polish measured a 107 ms median ([122, 101, 107]), so the
        // emulator bar is a 120 ms median of at least three, with a hard ceiling that still catches a real regression. Every value
        // is in timings.json; a phone measurement is still owed.
        assertTrue("At least seven warm shows measured: $warmShows", warmShows.size >= 7)
        val sorted = warmShows.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        assertTrue("Warm shows' median over 120 ms to the first dust frame: $warmShows", median <= 120.0)
        assertTrue("A warm show over 250 ms to the first dust frame: $warmShows", warmShows.all { it <= 250 })
    }
}
