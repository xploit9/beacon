package dev.hermes.android.agentphone

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.NativePairing
import kotlinx.coroutines.delay
import dev.hermes.android.journey.ownerRuntimeOptIn
import dev.hermes.android.loginLiveTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * G7 (EMU-L5 follow-up 1): the setup view against the REAL plugin on the DEMO Hermes (never the owner's), driven the way the owner
 * and TalkBack drive it: accessibility clicks on its buttons, real touches injected on the picture, text set in its text row.
 * Opt-in only (`-e beaconAgentPhoneLive main|restart` plus the owner-runtime flags the live journeys use), on an emulator, with the
 * demo sign-in staged by demo-fixture.py. What has to happen outside the app (a second viewer, the debugging prompt, the slow link,
 * reads of the agent phone, the screen recording) is asked of the host through files in this app's external files
 * (`l5-live/need-NN-<what>.json`, answered by `ack-NN-<what>`; .unlazy/emu-l5/fu1/tools/live_host.py watch). Every step is a JSON
 * line in `l5-live/steps.jsonl`, with the picture saved at each step; nothing typed is written there. Test data is synthetic.
 */
class AgentPhoneViewLiveTest {
    // A plain activity rule, not Compose's test rule: that one runs every composition in the process on its own test clock, and the
    // real view's effects (finishing on Done, opening the text row, the banners) would wait for the test to idle.
    private val scenario = ActivityScenarioRule(BeaconActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(scenario)

    private val args = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var dir: File
    private lateinit var vm: BeaconViewModel
    private lateinit var model: AgentPhoneViewModel
    private var asked = 0
    private var started = 0L

    // -- the record ----------------------------------------------------------------------------------------------------------------------
    private fun now() = SystemClock.elapsedRealtime()

    @Synchronized private fun note(step: String, vararg fields: Pair<String, Any?>) {
        val line = JSONObject().put("step", step).put("t", (now() - started) / 1000.0)
        fields.forEach { (k, v) -> line.put(k, v ?: JSONObject.NULL) }
        File(dir, "steps.jsonl").appendText(line.toString() + "\n")
    }

    /** Asks the host for something only it can do; with [wait] it waits for the host's answer. */
    private fun host(what: String, wait: Long = 90_000, vararg fields: Pair<String, Any?>): Boolean {
        val n = "%02d".format(++asked)
        File(dir, "need-$n-$what.json").writeText(JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }.toString())
        if (wait <= 0) return true
        val ack = File(dir, "ack-$n-$what")
        val end = now() + wait
        while (now() < end) { if (ack.exists()) return true; Thread.sleep(150) }
        note("host_no_answer", "what" to what)
        return false
    }

    private fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean): Long? {
        val t0 = now()
        while (now() - t0 < timeoutMs) {
            if (check()) return now() - t0
            Thread.sleep(80)
        }
        note("timeout", "what" to what, "phase" to runCatching { ui().phase.name }.getOrNull())
        return null
    }

    // -- the view ------------------------------------------------------------------------------------------------------------------------
    private fun beacon(): BeaconActivity { var a: BeaconActivity? = null; scenario.scenario.onActivity { a = it }; return checkNotNull(a) }
    private val app get() = instrumentation.targetContext.applicationContext as BeaconApplication

    private fun <T> main(read: () -> T): T { var out: Result<T>? = null; instrumentation.runOnMainSync { out = runCatching(read) }; return out!!.getOrThrow() }
    private fun ui(): ViewUi = main { model.session.ui.value }
    private fun shown(): ShownFrame? = main { model.decoder.shown.value }
    private fun version(): Long = shown()?.version ?: -1L
    private fun viewActivity(): AgentPhoneActivity? = main {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<AgentPhoneActivity>().firstOrNull()
    }

    private fun snap(name: String) {
        val frame = shown() ?: return
        val copy = main { frame.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false) }
        File(dir, "$name.jpg").outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun screen(name: String) {
        // The owner's whole screen as the system's screenshot sees it: with FLAG_SECURE held it should come out blank (that is the
        // proof); the picture itself is saved by snap().
        automation.takeScreenshot()?.let { shot -> File(dir, "$name-screen.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Opens the view the way L4's phone page does (by class name, no extras) and waits for the first picture. */
    private fun openView(label: String, capture: Boolean = true): Boolean {
        val t0 = now()
        val from = beacon()
        main { from.startActivity(Intent(from, AgentPhoneActivity::class.java)) }
        waitFor("view_activity", 15_000) { viewActivity() != null } ?: return false
        val activity = checkNotNull(viewActivity())
        main { model = ViewModelProvider(activity)[AgentPhoneViewModel::class.java] }
        val first = waitFor("first_frame", 120_000) { ui().phase == ViewPhase.Live && ui().hasFrame && shown() != null }
        val u = ui()
        note("$label:first_frame", "ms" to first?.let { now() - t0 }, "phase" to u.phase.name, "helper" to u.helper, "keyboard" to u.keyboard,
            "frame" to shown()?.let { "${it.width}x${it.height}" })
        if (capture) snap("$label-first")
        fullScreenNotice(label)
        return first != null
    }

    // -- the owner's hands -------------------------------------------------------------------------------------------------------------
    private val appPackage get() = instrumentation.targetContext.packageName

    /**
     * Every node on screen in [pkg]'s windows (Beacon's own by default; null = any window). Not only the active window: Android's own
     * "Viewing full screen" panel takes that place the first time a view goes immersive, and System UI has a "Back" of its own.
     */
    private fun nodes(pkg: String? = appPackage): List<AccessibilityNodeInfo> {
        // Fresh reads: a card that appears mid-run was missed from UiAutomation's node cache (17 Nov block).
        if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { automation.clearCache() }
        val roots = runCatching { automation.windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        roots.filter { pkg == null || it.packageName?.toString() == pkg }.forEach(::walk)
        return out
    }

    /** Android's one-time "Viewing full screen" panel over an immersive app: the owner taps Got it once. */
    private fun fullScreenNotice(view: String) {
        val t0 = now()
        while (now() - t0 < 3_000) {
            val got = nodes(null).firstOrNull { it.packageName?.toString() != appPackage && label(it) == "Got it" }
            if (got != null) {
                var p: AccessibilityNodeInfo? = got
                while (p != null && !p.isClickable) p = p.parent
                note("$view:full_screen_notice", "shown" to true, "dismissed" to (p ?: got).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(800)
                return
            }
            Thread.sleep(200)
        }
    }

    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text)?.toString().orEmpty()

    private fun find(match: (String) -> Boolean): AccessibilityNodeInfo? = nodes().firstOrNull { match(label(it)) }

    /** An accessibility click on the control named [name] (as TalkBack would), once it is there and enabled. */
    private fun click(name: String, timeoutMs: Long = 10_000): Boolean {
        val t0 = now()
        while (now() - t0 < timeoutMs) {
            val node = nodes().firstOrNull { label(it) == name && it.isEnabled && (it.isClickable || it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_CLICK }) }
                ?: nodes().firstOrNull { label(it) == name && it.isEnabled }?.let { var p = it; while (!p.isClickable) p = p.parent ?: break; p.takeIf { it.isClickable } }
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                note("click", "control" to name, "ok" to ok)
                return ok
            }
            Thread.sleep(150)
        }
        note("click_missing", "control" to name, "phase" to ui().phase.name)
        return false
    }

    private fun soft(what: String, ok: Boolean) { if (!ok) note("step_failed", "what" to what) }

    private fun shownText(contains: String): Boolean = find { it.contains(contains) } != null

    /** The picture's place on the screen, letterboxed as FrameCanvas draws it. */
    private fun pictureRect(): Rect? {
        val node = find { it.endsWith("phone screen") } ?: return null
        val bounds = Rect().also(node::getBoundsInScreen)
        val frame = shown() ?: return null
        val fit = fitFrame(bounds.width(), bounds.height(), frame.width, frame.height)
        return Rect(bounds.left + fit.left, bounds.top + fit.top, bounds.left + fit.left + fit.width, bounds.top + fit.top + fit.height)
    }

    private fun inject(action: Int, x: Float, y: Float, downAt: Long): Boolean {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return automation.injectInputEvent(event, true).also { event.recycle() }
    }

    private var heldAt = 0L
    private var held: Pair<Float, Float>? = null

    /** A real finger on the picture at fractions ([fx], [fy]) of the agent phone's screen. */
    private fun press(fx: Double, fy: Double): Boolean {
        val r = pictureRect() ?: return false
        val x = r.left + (fx * r.width()).toFloat()
        val y = r.top + (fy * r.height()).toFloat()
        heldAt = SystemClock.uptimeMillis()
        held = x to y
        return inject(MotionEvent.ACTION_DOWN, x, y, heldAt)
    }

    private fun lift(): Boolean {
        val (x, y) = held ?: return false
        held = null
        return inject(MotionEvent.ACTION_UP, x, y, heldAt)
    }

    private fun tap(fx: Double, fy: Double): Boolean {
        if (!press(fx, fy)) return false
        Thread.sleep(60)
        return lift()
    }

    /** The text row's own field: what the owner's keyboard (or password manager) puts there, set as an editor commit would. */
    private fun rowText(value: String): Boolean {
        val field = nodes().firstOrNull { it.className?.toString() == "android.widget.EditText" && it.isEnabled } ?: return false
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }

    private fun online(): Boolean = main {
        val cm = instrumentation.targetContext.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // -- Hermes' answers ---------------------------------------------------------------------------------------------------------------
    private fun phone(): JSONObject = runBlocking { checkNotNull(vm.client).nativeRequest(ViewProtocol.PHONE_PATH) }
    private fun lease(): JSONObject? = runCatching { phone().optJSONObject("lease") }.getOrNull()

    /** Bytes per second of one plain download from the demo's own address (the dashboard's largest script), to measure the link. */
    private fun probe(): Double? = runCatching {
        val origin = checkNotNull(vm.client).auth.origin.trimEnd('/')
        /** At most the first 256 KB, so a throttled link is measured in seconds, not minutes. */
        fun get(url: String): Pair<ByteArray, Long> {
            val t0 = now()
            val c = (URL(url).openConnection() as HttpURLConnection).apply { useCaches = false; connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("Cache-Control", "no-cache") }
            try {
                val out = java.io.ByteArrayOutputStream()
                c.inputStream.use { input -> val buffer = ByteArray(16_384); while (out.size() < 262_144) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) } }
                return out.toByteArray() to (now() - t0)
            } finally { c.disconnect() }
        }
        // The host names one of the dashboard's public scripts (its home page is a small sign-in page without one).
        val (bytes, ms) = get(origin + "/assets/" + checkNotNull(args.getString("probeFile")) { "no probeFile" })
        check(bytes.size >= 32_768) { "only ${bytes.size} bytes" }
        bytes.size * 1000.0 / ms.coerceAtLeast(1)
    }.onFailure { note("probe_failed", "error" to "${it.javaClass.simpleName}: ${it.message?.take(160)}") }.getOrNull()

    // -- sign-in -----------------------------------------------------------------------------------------------------------------------
    private fun signIn() = runBlocking {
        val staged = File(app.filesDir, "live-auth.json")
        if (staged.isFile) {
            val credentials = JSONObject(staged.readText())
            check(staged.delete() && credentials.string("purpose") == "beacon_visual_tour_demo")
            val origin = credentials.getString("origin")
            loginLiveTest(app, origin, credentials)
            val activity = beacon()
            vm = main { ViewModelProvider(activity)[BeaconViewModel::class.java] }
            withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
        } else { val activity = beacon(); vm = main { ViewModelProvider(activity)[BeaconViewModel::class.java] } }
        val t0 = now()
        while (vm.client == null && now() - t0 < 60_000) Thread.sleep(200)
        checkNotNull(vm.client) { "no client" }
        // Demo only: the profile's Hermes home is the isolated demo home.
        val home = checkNotNull(vm.client).nativeRpc("config.get", JSONObject().put("key", "profile")).optString("home").replace('\\', '/').trimEnd('/')
        check(home.endsWith("/hermes-demo") || home.contains("/hermes-demo/")) { "demo_home_required" }
    }

    private fun begin(part: String) {
        assumeTrue(args.getString("beaconAgentPhoneLive") == part)
        dir = File(app.getExternalFilesDir(null), if (part in setOf("remote", "voiceTools")) "live-$part" else "l5-live").apply { mkdirs() }
        // One run's record only: the host clears its own requests and answers before it starts watching.
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith("need-") && !it.name.startsWith("ack-") }?.forEach { it.delete() }
        started = now()
        // Window-wide reads (Android's own panels sit in windows of their own).
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        signIn()
        note("$part:signed_in", "phone" to runCatching { phone().optString("phone") }.getOrNull(), "lease" to lease()?.optString("holder"))
    }

    // -- G7: the main path, then everything else that needs no restart ---------------------------------------------------------------
    @Test(timeout = 240_000) fun remoteVoiceTools() {
        begin("voiceTools")
        runBlocking { checkRemoteVoiceTools(checkNotNull(vm.client), args.getString("runId").orEmpty(), args) { step, value -> note(step, "value" to value) } }
    }

    @Test(timeout = 240_000) fun remoteFrameCadence() {
        begin("remote")
        val client = checkNotNull(vm.client)
        val origin = client.auth.origin
        runBlocking {
            checkNotNull(NativePairing.link(app, client))
            client.nativeRpc("session.active_list")
            dev.hermes.android.data.HermesReach.failed(origin, java.net.ConnectException("Synthetic second connection refused"))
            client.nativeRpc("session.active_list")
            check(origin !in dev.hermes.android.data.HermesReach.origins.value)
        }
        note("remote:chat_replied")
        val roundTrips = (1..3).map {
            val at = now()
            runBlocking { client.nativeRpc("session.active_list") }
            now() - at
        }
        check(openView("remote", capture = false))
        try {
            if (args.getString("wideView") == "1") {
                val activity = checkNotNull(viewActivity())
                val (widthDp, heightDp) = main { activity.resources.configuration.run { screenWidthDp to screenHeightDp } }
                val steps = shownText("Setup steps")
                note("remote:layout", "width_dp" to widthDp, "height_dp" to heightDp, "steps_visible" to steps)
                check(widthDp >= 600 && heightDp >= 480 && widthDp >= heightDp * .75 && steps)
            }
            var previous = version()
            val times = mutableListOf<Long>()
            val start = now()
            note("remote:cadence_start", "run_id" to args.getString("runId"))
            var actions = 0
            while (now() - start < 20_000) {
                if (now() - start >= actions * 2_000L) {
                    val key = if (actions++ % 2 == 0) "recents" else "home"
                    check(main { model.session.key(key) })
                }
                val current = version()
                if (current != previous) { times += now(); previous = current }
                Thread.sleep(16)
            }
            note("remote:cadence", "round_trip_ms" to org.json.JSONArray(roundTrips),
                "frame_times_ms" to org.json.JSONArray(times.map { it - start }), "frames" to times.size)
            check(times.size >= 3 && times.last() - start > 12_000 && ui().phase == ViewPhase.Live)
        } finally { click("Done") }
    }

    @Test(timeout = 1_500_000) fun main() {
        begin("main")
        val fx = args.getString("emailX")!!.toDouble()
        val fy = args.getString("emailY")!!.toDouble()
        try {
            host("record-start", 30_000, "name" to "main")
            check(openView("main")) { "no first picture" }
            screen("main-live")

            // Touch: the shop's email field. The phone's keyboard comes up, so Beacon's text row opens by itself.
            val before = version()
            soft("tap_field", tap(fx, fy))
            val keyboard = waitFor("keyboard_state", 8_000) { ui().keyboard }
            val row = waitFor("text_row", 5_000) { nodes().any { it.className?.toString() == "android.widget.EditText" } }
            note("main:touch", "keyboard_ms" to keyboard, "row_ms" to row, "frames_after" to version() - before)
            snap("main-touched"); screen("main-row")

            // Text: ASCII first (paced keys), then a word in another alphabet (whole, through the helper's clipboard).
            val helper = waitFor("helper_ready", 20_000) { ui().helper == "ready" }
            note("main:helper", "ready_ms" to helper, "helper" to ui().helper)
            soft("row_ascii", rowText("qa.l5")); Thread.sleep(1_500)
            soft("row_other", rowText("qa.l5 café")); Thread.sleep(2_500)
            note("main:typed", "refused" to (ui().flash?.kind == FlashKind.TextRefused))
            // Paste from this phone's clipboard (the row closes first: the bar's Paste is under it).
            click("Hide keyboard")
            main { instrumentation.targetContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("l5", "+42")) }
            Thread.sleep(500)
            soft("paste", click("Paste"))
            Thread.sleep(3_000)
            note("main:pasted", "refused" to (ui().flash?.kind == FlashKind.PasteRefused))
            snap("main-typed"); screen("main-typed")
            host("check-text", 60_000, "expect" to "qa.l5 café+42")

            // Keys: Home, Recent apps, Back (the agent phone's own), each read on the phone by the host.
            for ((name, key) in listOf("Home screen" to "home", "Recent apps" to "recents", "Back" to "back")) {
                val v = version()
                soft("key_$key", click(name))
                val changed = waitFor("key_$key", 6_000) { version() > v }
                Thread.sleep(1_200)
                note("main:key", "key" to key, "frame_ms" to changed)
                snap("main-key-$key")
                host("check-focus", 30_000, "after" to key)
            }
            host("shop", 60_000)
            Thread.sleep(1_500)

            // Done: the view closes and the phone is the agent's again.
            val leaseBefore = lease()
            check(click("Done"))
            val closed = waitFor("closed", 6_000) { viewActivity() == null && ui().phase == ViewPhase.Closed }
            val back = waitFor("lease_agent", 10_000) { lease()?.optString("holder") == "agent" }
            note("main:done", "closed_ms" to closed, "lease_agent_ms" to back, "epoch_before" to leaseBefore?.optInt("epoch"), "epoch_after" to lease()?.optInt("epoch"))
            host("record-stop", 60_000, "name" to "main")

        } finally {
            held?.let { runCatching { lift() } }
            runCatching { shell("cmd connectivity airplane-mode disable") }
            host("end", 30_000)
        }
    }

    /**
     * The slow link: the emulator's speed setting throttles its mobile link, not its Wi-Fi, so the phone uses mobile data for this step
     * (measured on Wi-Fi, on mobile, then on throttled mobile). Then the picture lowers by itself: each speed gets 45 s of taps on the
     * field. Back to full speed and Wi-Fi, it comes back up.
     */
    private fun slowLink(fx: Double, fy: Double) {
        host("record-start", 30_000, "name" to "slow")
        val wifi = probe()
        var lowered: Long? = null
        shell("svc wifi disable")
        try {
            val mobile = waitFor("mobile_live", 60_000) { online() && ui().phase == ViewPhase.Live && ui().hasFrame }
            val fast = probe()
            note("more:mobile", "live_ms" to mobile, "wifi_bytes_per_s" to wifi, "fast_bytes_per_s" to fast, "phase" to ui().phase.name)
            for (speed in (args.getString("slowSpeeds") ?: "edge").split(',')) {
                host("slow-on", 30_000, "speed" to speed.replace('x', ':'))
                val slow = probe()
                val t1 = now()
                val latencies = mutableListOf<Long>()
                while (now() - t1 < 45_000 && lowered == null) {
                    val v = version()
                    val tapAt = now()
                    tap(fx, fy)
                    waitFor("tap_frame", 8_000) { version() > v }?.let { latencies += now() - tapAt }
                    if (ui().lowered) lowered = now() - t1
                    Thread.sleep(400)
                }
                note("more:link", "speed" to speed, "fast_bytes_per_s" to fast, "slow_bytes_per_s" to slow, "lowered_ms" to lowered,
                    "tap_to_frame_ms" to latencies.toString(), "flash" to ui().flash?.kind?.name)
                if (lowered != null) break
            }
            Thread.sleep(800); snap("lowered")
            note("more:lowered", "ms" to lowered)
            host("slow-off", 30_000)
        } finally {
            shell("svc wifi enable")
        }
        val raised = waitFor("raised", 60_000) {
            val v = version(); tap(fx, fy); waitFor("tap_frame", 4_000) { version() > v }; ui().phase == ViewPhase.Live && !ui().lowered
        }
        note("more:raised", "ms" to raised, "phase" to ui().phase.name)
        host("record-stop", 60_000, "name" to "slow")
    }

    /** Time to the first byte of a new connection to the dashboard (its public script), or null. */
    private fun firstByte(): Long? = runCatching {
        val origin = checkNotNull(vm.client).auth.origin.trimEnd('/')
        val t0 = now()
        val c = (URL(origin + "/assets/" + checkNotNull(args.getString("probeFile"))).openConnection() as HttpURLConnection)
            .apply { useCaches = false; connectTimeout = 20_000; readTimeout = 30_000; setRequestProperty("Cache-Control", "no-cache") }
        try { c.inputStream.use { it.read() }; now() - t0 } finally { c.disconnect() }
    }.onFailure { note("first_byte_failed", "error" to "${it.javaClass.simpleName}: ${it.message?.take(120)}") }.getOrNull()

    /**
     * The slow link as latency (Root, msg_0de3f33211e3: option A). `adb emu network speed` did not slow a view already open (block 5),
     * so the delay is set first (speed full) and the view opens after it, its socket born on the slow link. Measured before and after:
     * a download and a new connection's time to first byte. Then taps until the picture lowers; back to no delay, it rises again.
     */
    private fun slowDelay(fx: Double, fy: Double) {
        host("record-start", 30_000, "name" to "slow")
        val fast = probe()
        val fastFirst = firstByte()
        val delay = args.getString("slowDelay") ?: "gprs"
        // Block 6: on Wi-Fi the delay changed nothing (first byte 75 ms against 77). The emulator shapes its mobile link, so this
        // step runs on mobile data (Wi-Fi off; back on in the test's finally and in the block's teardown).
        shell("svc wifi disable")
        var mobile = waitFor("transport_cellular", 20_000) { transport() == "cellular" }
        if (mobile == null) { shell("cmd wifi set-wifi-enabled disabled"); mobile = waitFor("transport_cellular_cmd", 20_000) { transport() == "cellular" } }
        note("more:mobile", "ms" to mobile, "transport" to transport())
        host("delay-on", 30_000, "delay" to delay)
        val slow = probe()
        val slowFirst = firstByte()
        check(openView("slow"))
        val t1 = now()
        var lowered: Long? = null
        val latencies = mutableListOf<Long>()
        while (now() - t1 < 90_000 && lowered == null) {
            val v = version()
            val tapAt = now()
            tap(fx, fy)
            waitFor("tap_frame", 25_000) { version() > v }?.let { latencies += now() - tapAt }
            if (ui().lowered) lowered = now() - t1
            Thread.sleep(400)
        }
        note("more:link", "speed" to "delay:$delay", "fast_bytes_per_s" to fast, "slow_bytes_per_s" to slow, "fast_first_byte_ms" to fastFirst,
            "slow_first_byte_ms" to slowFirst, "lowered_ms" to lowered, "tap_to_frame_ms" to latencies.toString(), "flash" to ui().flash?.kind?.name)
        Thread.sleep(800); snap("lowered")
        note("more:lowered", "ms" to lowered)
        host("delay-off", 30_000)
        shell("svc wifi enable")
        val raised = waitFor("raised", 90_000) {
            val v = version(); tap(fx, fy); waitFor("tap_frame", 4_000) { version() > v }; ui().phase == ViewPhase.Live && !ui().lowered
        }
        note("more:raised", "ms" to raised, "phase" to ui().phase.name)
        host("record-stop", 60_000, "name" to "slow")
    }

    /** The slow link alone (a block of its own when the six's run could not measure it). */
    @Test(timeout = 900_000) fun slow() {
        begin("slow")
        val fx = args.getString("emailX")!!.toDouble()
        val fy = args.getString("emailY")!!.toDouble()
        try {
            if (args.getString("slowMode") == "delay") slowDelay(fx, fy) else { check(openView("slow")); slowLink(fx, fy) }
            val leaseBefore = lease()
            soft("done_after_slow", click("Done"))
            val back = waitFor("lease_agent", 10_000) { lease()?.optString("holder") == "agent" }
            note("slow:done", "lease_agent_ms" to back, "epoch_before" to leaseBefore?.optInt("epoch"))
        } finally {
            held?.let { runCatching { lift() } }
            runCatching { shell("svc wifi enable") }
            host("end", 30_000)
        }
    }

    // -- G7: the six that keep a view open a while: Open here, background, off/on, the debugging prompt, the slow link, Done offline --
    @Test(timeout = 1_500_000) fun six() {
        begin("six")
        val fx = args.getString("emailX")!!.toDouble()
        val fy = args.getString("emailY")!!.toDouble()
        try {
            host("record-start", 30_000, "name" to "takeover")
            check(openView("six"))
            // Replaced: another of the owner's phones opens the view; then "Open here" takes it back.
            host("takeover", 0)
            val replaced = waitFor("replaced", 30_000) { ui().phase == ViewPhase.Replaced }
            Thread.sleep(800); screen("replaced")
            note("more:replaced", "ms" to replaced, "card" to shownText("Opened on another phone"))
            check(click("Open here"))
            val live = waitFor("open_here_live", 30_000) { ui().phase == ViewPhase.Live && ui().hasFrame }
            note("more:open_here", "live_ms" to live, "lease_you" to lease()?.optBoolean("you"))
            host("takeover-end", 30_000)

            // Background and foreground: no frames while away, the lease kept, the picture back at once.
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor("stopped", 5_000) { viewActivity() == null }
            Thread.sleep(1_000)
            val away0 = version()
            val awayLease = lease()
            Thread.sleep(6_000)
            val away1 = version()
            // Back to the app (as from Recents): its task comes to the front with the view on top.
            main { instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, AgentPhoneActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            val front = waitFor("foreground", 10_000) { viewActivity() != null }
            val v0 = version()
            val resumed = waitFor("frames_resume", 10_000) { version() > v0 && ui().phase == ViewPhase.Live }
            note("more:background", "frames_while_away" to away1 - away0, "lease_holder_away" to awayLease?.optString("holder"),
                "lease_away_flag" to awayLease?.optBoolean("away"), "front_ms" to front, "frames_resume_ms" to resumed, "phase" to ui().phase.name)
            host("record-stop", 60_000, "name" to "takeover")
            host("record-start", 30_000, "name" to "turnon")

            // Phone off (from the phone page, through Hermes), then Turn on from the view.
            runBlocking { checkNotNull(vm.client).nativeRequest(ViewProtocol.PHONE_PATH + "/stop", "POST", JSONObject()) }
            val off = waitFor("stopped_card", 60_000) { ui().phase == ViewPhase.Stopped }
            Thread.sleep(800); screen("phone-off")
            note("more:off", "ms" to off, "card" to shownText("phone is off"))
            val t0 = now()
            check(click("Turn on"))
            val starting = waitFor("starting", 10_000) { ui().phase == ViewPhase.Starting }
            val on = waitFor("turned_on_live", 240_000) { ui().phase == ViewPhase.Live && ui().hasFrame }
            note("more:turn_on", "starting_ms" to starting, "live_ms" to on?.let { now() - t0 })
            snap("turned-on")
            host("record-stop", 60_000, "name" to "turnon")

            // The debugging prompt (raised by the host the way an app could): notice, input blocked, the held finger lifted, No.
            host("record-start", 30_000, "name" to "usb")
            host("shop", 60_000)
            Thread.sleep(2_000)
            soft("hold_finger", press(0.5, 0.75))
            host("usb", 0)
            val prompt = waitFor("prompt_on", 25_000) { ui().prompt }
            Thread.sleep(600); screen("prompt")
            val blockedKey = if (prompt != null) !main { model.session.key("home") } else null
            note("more:prompt", "ms" to prompt, "notice" to shownText("tried to take control"), "home_key_refused" to blockedKey)
            val cleared = if (prompt != null) waitFor("prompt_off", 25_000) { !ui().prompt } else null
            lift()
            Thread.sleep(800)
            val k0 = ui().keyboard
            soft("tap_after_prompt", tap(fx, fy))
            val touchAfter = waitFor("touch_after_prompt", 8_000) { ui().keyboard }
            note("more:prompt_end", "cleared_ms" to cleared, "keyboard_before_tap" to k0, "touch_works_ms" to touchAfter)
            host("usb-end", 30_000)
            host("record-stop", 60_000, "name" to "usb")
            slowLink(fx, fy)

            // Done pressed just as this phone lost its connection: the phone still goes back to the agent once it is online.
            host("record-start", 30_000, "name" to "offline")
            soft("live_before_offline", waitFor("live_before_offline", 30_000) { ui().phase == ViewPhase.Live } != null)
            shell("cmd connectivity airplane-mode enable")
            val dropped = waitFor("dropped", 40_000) { ui().phase != ViewPhase.Live }
            val phaseOffline = ui().phase.name
            soft("done_offline", click("Done"))
            val closedOffline = waitFor("closed_offline", 6_000) { viewActivity() == null }
            Thread.sleep(4_000)
            shell("cmd connectivity airplane-mode disable")
            val backOnline = waitFor("online", 30_000) { online() }
            val handedBack = waitFor("handed_back", 70_000) { lease()?.optString("holder") == "agent" }
            note("more:offline_done", "drop_ms" to dropped, "phase" to phaseOffline, "closed_ms" to closedOffline, "online_ms" to backOnline,
                "lease_agent_ms" to handedBack, "lease" to lease()?.toString())
            host("record-stop", 60_000, "name" to "offline")

            // A word in another alphabet goes whole through the helper's clipboard, then Done at once: the host reads the agent phone's
            // own screen right after it, where no clipboard preview may be left.
            host("record-start", 30_000, "name" to "paste")
            check(openView("paste"))
            host("shop", 60_000)
            Thread.sleep(2_000)
            soft("tap_field_paste", tap(fx, fy))
            val pasteRow = waitFor("paste_row", 8_000) { nodes().any { it.className?.toString() == "android.widget.EditText" } }
            val pasteHelper = waitFor("paste_helper", 20_000) { ui().helper == "ready" }
            soft("paste_ascii", rowText("qa.l5")); Thread.sleep(1_500)
            soft("paste_other", rowText("qa.l5 café"))
            val pastedAt = now()
            Thread.sleep(1_500)
            val pasteRefused = ui().flash?.kind == FlashKind.TextRefused
            snap("paste-before-done")
            soft("done_after_paste", click("Done"))
            val closedPaste = waitFor("closed_after_paste", 6_000) { viewActivity() == null }
            val doneAfter = now() - pastedAt
            host("agent-screen", 60_000, "name" to "after-paste-done")
            note("more:paste_done", "row_ms" to pasteRow, "helper_ms" to pasteHelper, "refused" to pasteRefused, "closed_ms" to closedPaste,
                "done_after_paste_ms" to doneAfter)
            host("check-text", 60_000, "expect" to "qa.l5 café")
            host("record-stop", 60_000, "name" to "paste")
        } finally {
            held?.let { runCatching { lift() } }
            runCatching { shell("cmd connectivity airplane-mode disable") }
            runCatching { shell("svc wifi enable") }
            host("end", 30_000)
        }
    }

    // -- the emulator's link shaping itself, without Hermes: a file from the PC (10.0.2.2), Wi-Fi and mobile, full and throttled -------
    private fun transport(): String = main {
        val cm = instrumentation.targetContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    /** A new connection: at most [cap] bytes or [deadlineMs], whichever comes first; what arrived and how fast, even when it failed. */
    private fun download(url: String, cap: Int, deadlineMs: Long): Array<Pair<String, Any?>> {
        val t0 = now()
        var n = 0
        var error: String? = null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { useCaches = false; connectTimeout = 10_000; readTimeout = 10_000 }
            try {
                c.inputStream.use { input -> val b = ByteArray(16_384); while (n < cap && now() - t0 < deadlineMs) { val k = input.read(b); if (k < 0) break; n += k } }
            } finally { c.disconnect() }
        } catch (e: Exception) { error = "${e.javaClass.simpleName}: ${e.message?.take(80)}" }
        val ms = now() - t0
        return arrayOf("bytes" to n, "ms" to ms, "bytes_per_s" to n * 1000L / ms.coerceAtLeast(1), "error" to error)
    }

    /** A connection already open (as the view's socket is): read at full speed, throttle, then bytes per second in 1 s buckets. */
    private fun openThenThrottle(url: String, speed: String): Array<Pair<String, Any?>> {
        var before = 0L
        val buckets = mutableListOf<Int>()
        var error: String? = null
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { useCaches = false; connectTimeout = 10_000; readTimeout = 15_000 }
            try {
                c.inputStream.use { input ->
                    val b = ByteArray(8_192)
                    var n = 0
                    val t0 = now()
                    while (n < 262_144) { val k = input.read(b); if (k < 0) break; n += k }
                    before = n * 1000L / (now() - t0).coerceAtLeast(1)
                    host("slow-on", 30_000, "speed" to speed)
                    repeat(8) {
                        val t1 = now()
                        var got = 0
                        while (now() - t1 < 1_000) { val k = input.read(b); if (k < 0) break; got += k }
                        buckets += got
                    }
                }
            } finally { c.disconnect() }
        } catch (e: Exception) { error = "${e.javaClass.simpleName}: ${e.message?.take(80)}" }
        host("slow-on", 30_000, "speed" to "full")
        return arrayOf("before_bytes_per_s" to before, "after_bytes_per_s_by_second" to buckets.toString(), "error" to error)
    }

    @Test(timeout = 900_000) fun net() {
        assumeTrue(args.getString("beaconAgentPhoneLive") == "net")
        dir = File(app.getExternalFilesDir(null), "l5-live").apply {
            mkdirs(); listFiles()?.filter { it.isFile && !it.name.startsWith("need-") && !it.name.startsWith("ack-") }?.forEach { it.delete() }
        }
        started = now()
        val base = "http://10.0.2.2:${args.getString("probePort")}"
        val speed = args.getString("slowSpeeds") ?: "edge"
        try {
            note("net:start", "transport" to transport(), "small" to args.getString("probeFile"))
            for (mobile in listOf(false, true)) {
                val want = if (mobile) "cellular" else "wifi"
                shell(if (mobile) "svc wifi disable" else "svc wifi enable")
                var on = waitFor("transport_$want", 20_000) { transport() == want }
                if (on == null) { shell(if (mobile) "cmd wifi set-wifi-enabled disabled" else "cmd wifi set-wifi-enabled enabled"); on = waitFor("transport_${want}_cmd", 20_000) { transport() == want } }
                note("net:transport", "want" to want, "ms" to on, "now" to transport())
                for (s in listOf("full", speed)) {
                    host("slow-on", 30_000, "speed" to s.replace('x', ':'))
                    note("net:new", "transport" to transport(), "speed" to s, *download("$base/${args.getString("probeFile")}", 196_608, 20_000))
                }
                host("slow-on", 30_000, "speed" to "full")
                note("net:open", "transport" to transport(), "speed" to speed, *openThenThrottle("$base/big.bin", speed.replace('x', ':')))
            }
        } finally {
            runCatching { shell("svc wifi enable") }
            host("end", 30_000)
        }
    }

    // -- the harness itself, on any test emulator, offline (nothing reaches any Hermes) ------------------------------------------------
    @Test(timeout = 180_000) fun harness() {
        assumeTrue(args.getString("beaconAgentPhoneLive") == "harness")
        dir = File(app.getExternalFilesDir(null), "l5-live").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        started = now()
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("cmd connectivity airplane-mode enable")
        try {
            waitFor("offline", 20_000) { !online() }
            val from = beacon()
        main { from.startActivity(Intent(from, AgentPhoneActivity::class.java)) }
            waitFor("view_activity", 15_000) { viewActivity() != null }
            val activity = checkNotNull(viewActivity())
            main { model = ViewModelProvider(activity)[AgentPhoneViewModel::class.java] }
            fullScreenNotice("harness")
            val done = waitFor("done_found", 10_000) { nodes().any { label(it) == "Done" } }
            // The end card for this phase is drawn by the view's own frames (Compose's test clock is not in charge here).
            val card = waitFor("card", 15_000) { ui().phase != ViewPhase.Connecting && nodes().any { it.text?.toString()?.let { s -> "Hermes" in s || "phone" in s || "moment" in s || "Reconnecting" in s } == true } }
            note("harness:card", "ms" to card, "phase" to ui().phase.name, "texts" to nodes().mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }.take(8).toString())
            val bar = listOf("Back", "Home screen", "Recent apps", "Keyboard", "Paste").associateWith { name -> nodes().any { label(it) == name } }
            val systemBack = nodes(null).count { label(it) == "Back" } - nodes().count { label(it) == "Back" }
            note("harness", "phase" to ui().phase.name, "done_found_ms" to done, "bar" to JSONObject(bar.mapValues { it.value }).toString(),
                "windows" to automation.windows.map { it.root?.packageName?.toString() }.toString(), "system_back_nodes" to systemBack)
            check(click("Done"))
            note("harness:closed", "ms" to waitFor("closed", 6_000) { viewActivity() == null })
        } finally {
            // Airplane mode stays on until the host turns it off after this process has ended: Done offline starts the hand-back's
            // retries, which must not reach whatever Hermes this test device is signed in to.
        }
    }

    // -- AGENTUX-121 idea 7: the two panes, live (the host lowers this test phone's density to an unfolded width first, then resets it) --
    @Test(timeout = 300_000) fun panes() {
        begin("panes")
        try {
            host("record-start", 30_000, "name" to "panes")
            check(openView("panes")) { "no first picture" }
            Thread.sleep(3_000)
            // Two panes: the picture's place is narrower than the screen, and the setup steps are read beside it, not in a sheet.
            val picture = find { it.endsWith("phone screen") }?.let { Rect().also(it::getBoundsInScreen) }
            val screenWidth = main { beacon().resources.displayMetrics.widthPixels }
            note("panes:layout", "width_dp" to main { beacon().resources.configuration.screenWidthDp }, "picture_px" to picture?.width(), "screen_px" to screenWidth,
                "steps_beside" to shownText("Setup steps"), "done" to (find { it == "Done" } != null))
            snap("panes-live")
            Thread.sleep(4_000)
            check(click("Done"))
            note("panes:closed", "ms" to waitFor("closed", 6_000) { viewActivity() == null })
            host("record-stop", 60_000, "name" to "panes")
        } finally {
            host("end", 30_000)
        }
    }

    // -- G7: a Hermes restart under an open view ---------------------------------------------------------------------------------------
    @Test(timeout = 1_500_000) fun restart() {
        begin("restart")
        try {
            host("record-start", 30_000, "name" to "restart")
            check(openView("restart"))
            host("restart-ready", 0)
            val t0 = now()
            val away = waitFor("restart_seen", 1_200_000) { ui().phase in setOf(ViewPhase.BackSoon, ViewPhase.Reconnecting, ViewPhase.HermesDown) }
            val phaseSeen = ui().phase.name
            Thread.sleep(1_000); screen("restart-away")
            val shownWords = when { shownText("Back in a moment") -> "Back in a moment"; shownText("Reconnecting") -> "Reconnecting…"; else -> null }
            val lostAt = now()
            val back = waitFor("restart_live", 600_000) { ui().phase == ViewPhase.Live && ui().hasFrame }
            note("restart", "seen_after_ms" to away?.let { lostAt - t0 }, "phase" to phaseSeen, "words" to shownWords, "live_again_ms" to back)
            snap("restart-back"); screen("restart-back")
            check(click("Done"))
            val handed = waitFor("lease_agent", 10_000) { lease()?.optString("holder") == "agent" }
            note("restart:done", "lease_agent_ms" to handed)
            host("record-stop", 60_000, "name" to "restart")
        } finally {
            host("end", 30_000)
        }
    }
}
