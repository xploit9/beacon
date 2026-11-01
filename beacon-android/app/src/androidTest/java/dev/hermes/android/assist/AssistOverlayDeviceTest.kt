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
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.normalized
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Lanes assist-overlay, ux-overlay and ux2-overlay: Beacon's own assistant overlay on the emulator, end to end. It makes Beacon the phone's
 * assistant for the run (cmd role, test only), opens the overlay over the home screen, over another app and over the lock screen,
 * asks in writing, listens, starts a live call without the app coming to the front, checks that a locked answer waits behind the
 * lock row and that the chips fit whole, that the first summon's "Talk live" hint ends on the pill's end (mirrored in Arabic) with the
 * chips held until it has faded, that a failed send goes back to the pill and a failed call shows its short reason, and takes the
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

    private fun capture(name: String, settle: Long = 700) {
        instrumentation.waitForIdleSync()
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

    /** What the overlay and the assistant chat hold, for a failure message. */
    private fun overlayState(): String {
        val c = session()?.controller ?: return "no session"
        val vm = c.vm
        val ui = vm?.ui?.value
        val chat = vm?.chat?.value
        return "overlay=${c.state.copy(ask = c.state.ask?.copy(answer = c.state.ask?.answer?.take(80).orEmpty()))} " +
            "ui=[auth=${ui?.authenticated} connected=${ui?.connected} tab=${ui?.tab} opening=${ui?.beaconOpening} beaconError=${ui?.beaconError} notice=${ui?.notice} error=${ui?.error}] " +
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
     * The first summon's hint: the capsule's end sits on the pill's end (its right edge, or its left in Arabic, 16 dp from the screen)
     * with the pointer over the orb, and the chips are still held while it shows. The numbers are read the moment it appears, since it
     * stays for 1.5 s; the still is taken a moment later, once it has faded in.
     */
    private fun assertHintAligned(look: Look, shot: String) {
        waitFor("the talk hint in ${look.name}", 6_000) { hasTag("assist-talk-hint") }
        val stage = controller().hint
        val (hint, pill, orb) = boundsOf("assist-talk-hint", "assist-pill", "assist-live")
        val width = main { requireNotNull(session()?.window?.window?.decorView).width }
        Thread.sleep(250)
        capture(shot, settle = 0)
        val density = app.resources.displayMetrics.density
        timings.put("hint_${look.name}", JSONObject().put("hint", JSONArray(hint.toList())).put("pill", JSONArray(pill.toList())).put("orb", JSONArray(orb.toList()))
            .put("screen_width", width).put("stage", stage.name))
        assertEquals("The chips wait while the hint shows in ${look.name}", HintStage.Playing, stage)
        val gap = if (look.arabic) hint[0] - pill[0] else pill[1] - hint[1]
        assertTrue("The hint's end sits on the pill's end in ${look.name}: hint=${hint.toList()} pill=${pill.toList()}", kotlin.math.abs(gap) <= 2f)
        val margin = if (look.arabic) pill[0] else width - pill[1]
        assertTrue("The pill's end is 16 dp from the screen in ${look.name}: $margin px", kotlin.math.abs(margin - 16 * density) <= 3f)
        val orbCentre = (orb[0] + orb[1]) / 2f
        assertTrue("The pointer falls over the orb in ${look.name}: orb at $orbCentre inside ${hint.toList()}", orbCentre >= hint[0] && orbCentre <= hint[1])
    }

    /** Locked, the finished answer waits behind the lock row: the row shows, and not a word of the answer does. */
    private fun assertLockedAnswerHidden(look: Look) {
        val ask = requireNotNull(controller().state.ask)
        // The controller's state changes a frame before the semantics tree does, so the lock row is waited for (and how long it took is
        // recorded); at no poll in that time may any of the answer's text be on screen.
        val started = SystemClock.uptimeMillis()
        val atOnce = hasTag("assist-unlock-to-see")
        waitFor("the lock row in ${look.name}: ${overlayState()}", 8_000) {
            assertFalse("A locked answer never shows its text in ${look.name}", hasTag("assist-answer-text"))
            hasTag("assist-unlock-to-see")
        }
        timings.put("lock_row_at_once_${look.name}", atOnce).put("lock_row_wait_ms_${look.name}", SystemClock.uptimeMillis() - started)
        assertTrue("A locked answer shows the lock row in ${look.name}", hasTag("assist-unlock-to-see"))
        assertFalse("A locked answer never shows its text in ${look.name}", hasTag("assist-answer-text"))
        val words = ask.answer.trim().take(24)
        assertTrue("The answer's words show locked in ${look.name}", words.isBlank() || shownTexts().none { it.contains(words) })
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
        val hints = app.getSharedPreferences(AssistOverlayController.HINTS, Context.MODE_PRIVATE)
        val hintSeen = hints.getBoolean(AssistOverlayController.TALK_HINT_SEEN, false)
        try {
            shell("cmd role add-role-holder android.app.role.ASSISTANT $pkg")
            waitFor("Beacon to become the assistant", 20_000) { shell("settings get secure voice_interaction_service") == assistant }
            assertEquals(PhoneAssistantState.Active, phoneAssistantState(app))

            // 1. Over the home screen: the cold first show says once what the orb is for, then the overlay's own pill and chips.
            hints.edit().remove(AssistOverlayController.TALK_HINT_SEEN).commit()
            wear(looks[0])
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(1_500)
            timings.put("cold_first_frame_ms", summon())
            assertFalse("The overlay must not bring the app to the front", beaconInFront())
            val hinted = runCatching { waitFor("the talk hint", 6_000) { hasTag("assist-talk-hint") } }.isSuccess
            timings.put("talk_hint_shown", hinted)
            assertTrue("The first summon shows the hint", hinted)
            assertHintAligned(looks[0], "00-talk-hint-dark")
            // The chips rise after the hint has faded, never under it.
            waitFor("the hint to fade", 6_000) { controller().hint == HintStage.Over && !hasTag("assist-talk-hint") }
            assertTrue("The talk hint is shown once, then remembered", hints.getBoolean(AssistOverlayController.TALK_HINT_SEEN, false) || !hinted)
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

            // 2b. The first summon's hint in the other three looks, over Settings: end on the pill's end, mirrored in Arabic.
            for (look in looks.drop(1)) {
                hints.edit().remove(AssistOverlayController.TALK_HINT_SEEN).commit()
                wear(look)
                Thread.sleep(1_500)
                summon()
                if (look.arabic) assertEquals("ar", controller().locale.language)
                assertHintAligned(look, "00-talk-hint-${look.name}")
                waitFor("the hint to fade", 6_000) { controller().hint == HintStage.Over && !hasTag("assist-talk-hint") }
                dismissWithBack()
            }
            // Remembered: the next summon shows no hint and lets the chips rise at once.
            wear(looks[0])
            Thread.sleep(1_500)
            summon()
            Thread.sleep(600)
            assertFalse("The hint shows once", hasTag("assist-talk-hint"))
            assertEquals(HintStage.Over, controller().hint)
            dismissWithBack()

            // 3. Over an app in every look: the bare pill and its chips, a written answer, a send that failed, a call that didn't start.
            for (look in looks) {
                wear(look)
                summon()
                if (look.arabic) assertEquals("ar", controller().locale.language)
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
            main { controller().dictate() }
            waitFor("dictation to start", 5_000) { controller().state.dictating || controller().state.line != null }
            if (controller().state.dictating) { capture("07-listening"); frames("listening", 2_000) }
            timings.put("dictation", if (controller().state.dictating) "listening" else controller().state.line.toString())
            main { if (controller().state.dictating) controller().dictate() }
            Thread.sleep(1_000)

            // 5. A live call from the pill: it reaches connecting or live while the app stays in the background; stills in every look.
            main { controller().clearLine(); controller().startLive() }
            waitFor("the live call to start", 45_000) { app.calls.state.value.active && app.calls.state.value.state != "idle" }
            assertEquals(OverlaySurface.Live, controller().state.surface)
            assertFalse("A call from the overlay must not open the app", beaconInFront())
            timings.put("call_state", app.calls.state.value.state)
            capture("app-live-dark")
            val spoke = runCatching { waitFor("the assistant to speak", 25_000) { presenceState(app.calls.state.value) == SphereState.Speaking } }.isSuccess
            timings.put("speaking_seen", spoke)
            if (spoke) { capture("09-speaking"); frames("speaking", 2_000) }
            // After the greeting, so measuring never hides it: the band's frames, then typing mid-call. The keyboard control opens
            // the typing row; the still shows both, then the row closes again.
            frames("live")
            if (app.calls.state.value.active && clickTag("assist-live-type")) {
                Thread.sleep(800); capture("08b-live-typing"); timings.put("typing_still", true)
                main { controller().draft = TextFieldValue() }
                clickTag("assist-live-type"); Thread.sleep(500)
            } else timings.put("typing_still", false)
            for (look in looks.drop(1)) {
                if (!app.calls.state.value.active) break
                if (look.arabic) dismissWithBack()
                wear(look)
                // The language is read on a summon; a running call opens straight into the live band.
                if (look.arabic) { summon(); assertEquals(OverlaySurface.Live, controller().state.surface) }
                // A new text size restarts the app beneath; the still waits for it to draw again.
                Thread.sleep(1_500)
                capture("app-live-${look.name}")
                if (look.arabic) { dismissWithBack(); wear(looks[0]); summon() }
            }
            assertFalse(beaconInFront())
            main { controller().endCall() }
            waitFor("the call to end", 45_000) { !app.calls.state.value.active }
            dismissWithBack()
            wear(looks[0])

            // 6. Over the lock screen in every look: the bare pill, then a written answer that waits behind the lock row.
            shell("locksettings set-disabled false")
            shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1_500); shell("input keyevent KEYCODE_WAKEUP")
            val keyguard = requireNotNull(app.getSystemService(KeyguardManager::class.java))
            waitFor("the lock screen", 10_000) { keyguard.isKeyguardLocked }
            val power = requireNotNull(app.getSystemService(android.os.PowerManager::class.java))
            for (look in looks) {
                wear(look)
                // The lock screen sleeps ten seconds after it wakes, and the system hides the overlay with the screen. The open overlay
                // holds the screen for half a minute after the last interaction, so each look wakes the phone afresh, pauses 15 s (past
                // the phone's own timeout) with the overlay still open, and asks inside the half minute; the frame measurement waits
                // until the answer holds the screen on.
                shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1_000)
                shell("input keyevent KEYCODE_WAKEUP"); Thread.sleep(500)
                summon()
                assertTrue("The overlay knows it is over the lock screen", controller().locked)
                assertTrue(keyguard.isKeyguardLocked)
                capture("lock-idle-${look.name}")
                // A pause to think over on the lock screen: still open, screen still on, nothing touched for 15 s.
                Thread.sleep(15_000)
                assertTrue("The overlay is still open after a 15 s pause on the lock screen in ${look.name}: ${overlayState()}",
                    controller().state.phase == OverlayPhase.Open && power.isInteractive && keyguard.isKeyguardLocked && hasTag("assist-pill"))
                capture("lock-idle-15s-${look.name}")
                assertTrue("The lock screen is still on, with the overlay open, when the question is asked in ${look.name}: ${overlayState()}",
                    power.isInteractive && controller().state.phase == OverlayPhase.Open)
                askAndWait("Without using any tools, reply in one short sentence: what colour is the sky on a clear day?")
                assertTrue("Still locked while answering", keyguard.isKeyguardLocked)
                // The lock screen sleeps ten seconds after the last touch; the overlay holds it on while it answers.
                assertTrue("The screen stayed on over the lock screen", requireNotNull(app.getSystemService(android.os.PowerManager::class.java)).isInteractive)
                assertTrue(controller().state.phase == OverlayPhase.Open)
                assertLockedAnswerHidden(look)
                capture("lock-answer-${look.name}")
                // Measured with the answer up, which keeps the screen on for the measurement.
                if (look.name == "dark") frames("locked")
                dismissWithBack()
            }
            shell("wm dismiss-keyguard")
        } finally {
            runCatching { app.calls.hangup() }
            runCatching { session()?.let { s -> main { s.hide() } } }
            hints.edit().putBoolean(AssistOverlayController.TALK_HINT_SEEN, hintSeen).commit()
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
