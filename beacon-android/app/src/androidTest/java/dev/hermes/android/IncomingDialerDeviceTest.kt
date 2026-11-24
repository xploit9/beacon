package dev.hermes.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.flow.first
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.push.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production push, notification, Activity and decline/expiry. All HTTPS requests end at a single-device in-process fixture. */
class IncomingDialerDeviceTest {
    @get:Rule val host = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun visibleNode(packageName: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { next -> find(next)?.let { return it } }
            return null
        }
        return automation.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName == packageName }?.let(::find)
        }
    }

    private fun visibleLabel(packageName: String, labels: Set<String>) =
        visibleNode(packageName) { it.text?.toString() in labels || it.contentDescription?.toString() in labels }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(80)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Call-state condition did not arrive in ${timeoutMs} ms")
    }

    private fun shadeShows(labels: Set<String>) = visibleLabel("com.android.systemui", labels) != null

    @Test fun genuineCallCardAndLockScreenSettleAtTheFixture() = runBlocking<Unit> {
        assumeTrue("Requires the dedicated 5578 lease", InstrumentationRegistry.getArguments().getString("fixtureSerial") == "emulator-5578")
        check(Build.VERSION.SDK_INT >= 31 && (shell("getprop ro.kernel.qemu").trim() == "1" || Build.HARDWARE in setOf("ranchu", "goldfish")))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val fixture = NativeExperienceFixture("dialer-test-owner")
        val origin = fixture.scope.origin
        val original = app.auth.read(origin)
        val originKey = stringPreferencesKey("origin")
        val previousOrigin = app.preferences.data.first()[originKey]
        var returned: BeaconActivity? = null
        val auth = AuthContext(origin, emptyList(), fixture.scope.owner, fixture.scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 600_000,
                origin, fixture.scope.owner, fixture.scope.profile, "fixture"))
        val client = fixture.clientFor(auth, app.auth)
        val prefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val pairPrefs = app.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
        val pairKey = NativePairing.accountKey(fixture.scope)
        val oldPair = pairPrefs.getString(pairKey, null)
        val manager = app.getSystemService(NotificationManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val audio = app.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
        val oldRinger = audio.ringerMode
        val events = mutableListOf<Pair<String, String>>()
        val info = automation.serviceInfo
        val oldFlags = info.flags
        check(KeepConnectedService.clientFactoryForTest == null && !app.calls.state.value.active)
        check(!keyguard.isKeyguardLocked && !keyguard.isKeyguardSecure) { "The presentation fixture requires an unsecured test keyguard" }
        val out = checkNotNull(app.getExternalFilesDir("callscreen-123"))
        host.scenario.onActivity { it.setContent { BeaconTheme(dark = true) { Surface(Modifier.fillMaxSize()) { Text("Call test") } } } }
        fun clip(name: String, seconds: Int, action: () -> Unit) {
            val enabled = InstrumentationRegistry.getArguments().getString("systemMedia") == "yes"
            val video = File(out, "$name.mp4")
            val descriptor = if (enabled) automation.executeShellCommand("screenrecord --bit-rate 3000000 --time-limit $seconds ${video.absolutePath}") else null
            try {
                if (descriptor != null) SystemClock.sleep(1200)
                action()
            } finally {
                descriptor?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { pipe -> pipe.readBytes() } }
            }
            if (enabled) assertTrue(video.length() > 1024)
        }
        fun card() = manager.activeNotifications.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.notification
        try {
            automation.serviceInfo = info.apply { flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            app.auth.write(auth)
            KeepConnectedService.clientFactoryForTest = { current, store ->
                check(current.origin == origin)
                fixture.clientFor(current, store)
            }
            val paired = checkNotNull(NativePairing.link(app, client))
            suspend fun ring(seconds: Long): JSONObject {
                val only = NativePairing.devices(client).single()
                check(only.id == paired && NativePairing.deviceId(app, fixture.scope) == only.id)
                File(out, "fixture-binding.txt").appendText("emulator-5578 / isolated HTTPS fixture / exactly one linked device / local ID matched / " +
                    "device digest " + only.id.digest().take(12) + " / before ring " + System.currentTimeMillis() + "\n")
                val event = fixture.emitIncomingCall(expiresInSeconds = seconds)
                val source = event.getString("source_ref")
                val id = event.getString("event_id")
                events += source to id
                val key = "source:${source.digest()}"
                val binding = json("source" to source, "origin" to origin, "owner" to fixture.scope.owner,
                    "profile" to fixture.scope.profile, "native_provider" to fixture.scope.nativeProvider, "backend" to "hermes-dashboard",
                    "device_id" to only.id, "target" to "fixture-target")
                check(!prefs.contains(key))
                persistPushBindings(prefs, mapOf(key to Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), key), Base64.NO_WRAP)))
                receivePush(app, listOf("source_ref","event_id","conversation_ref","kind","expires_at").associateWith { event.getString(it) })
                return event
            }
            if (volume == 0) audio.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
            check(audio.ringerMode == AudioManager.RINGER_MODE_NORMAL)
            val unlocked = ring(45)
            waitFor(8000) { card()?.actions?.size == 2 }
            val posted = checkNotNull(card())
            assertEquals("android.app.Notification\$CallStyle", posted.extras.getString(Notification.EXTRA_TEMPLATE))
            // No countdown digits on the system card either.
            assertFalse(posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertFalse(posted.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            val titles = posted.actions.map { it.title.toString() }.toSet()
            waitFor(5000) { shadeShows(titles) }
            assertNotNull(posted.fullScreenIntent)
            clip("system-unlocked-decline", 6) {
                posted.actions.single { it.actionIntent.isBroadcast }.actionIntent.send()
            }
            waitFor(8000) { fixture.incomingNotification?.optString("event_id") == unlocked.getString("event_id") &&
                fixture.incomingNotification?.optString("state") == "declined" && card() == null }

            if (Build.VERSION.SDK_INT >= 34) check(manager.canUseFullScreenIntent()) { "Full-screen call permission is off" }
            shell("input keyevent KEYCODE_SLEEP")
            waitFor(8000) { keyguard.isKeyguardLocked }
            // R2-F8: the app's tap haptic must be played, not dropped for the ring vibration that was still going.
            fun touchHaptics() = shell("dumpsys vibrator_manager").lineSequence().filter { "usage: TOUCH" in it && app.packageName in it }.toList()
            val hapticsBefore = touchHaptics().size
            val locked = ring(45)
            val declineLabel = setOf(serviceText(app, "Decline"))
            waitFor(12000) { visibleLabel(app.packageName, declineLabel) != null }
            check(shell("dumpsys activity activities").lineSequence().any { "topResumedActivity=" in it && "IncomingDialerActivity" in it })
            assertTrue("Private content stays behind the keyguard", keyguard.isKeyguardLocked)
            clip("system-locked-decline", 7) {
                // R2-F7: a real touch, as soon as the buttons are drawn, not an accessibility click.
                val button = checkNotNull(visibleLabel(app.packageName, declineLabel))
                val bounds = android.graphics.Rect().also { generateSequence(button) { it.parent }.first { it.isClickable && it.isEnabled }.getBoundsInScreen(it) }
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            }
            waitFor(8000) { fixture.incomingNotification?.optString("event_id") == locked.getString("event_id") &&
                fixture.incomingNotification?.optString("state") == "declined" }
            val newHaptics = touchHaptics().drop(hapticsBefore)
            assertTrue("the Decline tap haptic is recorded: $newHaptics", newHaptics.isNotEmpty())
            assertTrue("the Decline haptic is not outranked by the ring vibration: $newHaptics", newHaptics.none { "ignored_for_higher_importance" in it })
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            waitFor(8000) { !keyguard.isKeyguardLocked && card() == null }

            val missed = ring(8)
            val source = missed.getString("source_ref")
            val id = missed.getString("event_id")
            waitFor(15000) { app.push.missedInvitation(source, id) != null }
            val invitation = checkNotNull(app.push.missedInvitation(source, id))
            val callback = manager.activeNotifications.single { it.tag == invitation.key && it.id == 2 }.notification
            assertTrue(callback.actions.single().actionIntent.isActivity)
            assertNotNull(callback.contentIntent)
            assertEquals(callback.contentIntent, callback.actions.single().actionIntent)
            // Keep the notification shade on this unsecured test lock, with the synthetic fixture in the foreground behind it.
            if (InstrumentationRegistry.getArguments().getString("systemMedia") == "yes") {
                waitFor(5000) { visibleLabel(app.packageName, setOf("Call test")) != null }
                try {
                    clip("system-missed-callback-action", 8) {
                        shell("cmd statusbar expand-notifications")
                        waitFor(5000) { shadeShows(setOf(callback.extras.getCharSequence(Notification.EXTRA_TITLE).toString())) }
                        SystemClock.sleep(1200)
                        shell("cmd statusbar collapse")
                    }
                } finally { shell("cmd statusbar collapse") }
            }
            // Follow the actual system action with the microphone denied. No voice provider or key screen is needed.
            check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED)
            app.preferences.edit { it[originKey] = origin }
            val binding = checkNotNull(app.push.binding(source))
            val routeClient = app.push.clientFor(binding)
            try {
                val route = routeClient.nativeRequest("/api/plugins/beacon/mobile/notifications/events/$id")
                dev.hermes.android.voice.requireMissedCallbackRoute(route, invitation, routeClient.scope)
            } finally { routeClient.closeNative() }
            shell("cmd statusbar expand-notifications")
            val callbackLabel = setOf(callback.actions.single().title.toString())
            waitFor(6000) { shadeShows(callbackLabel) }
            val backAction = checkNotNull(visibleLabel("com.android.systemui", callbackLabel))
            check(generateSequence(backAction) { it.parent }.first { it.isEnabled && it.isClickable }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            fun denialButton() = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller").firstNotNullOfOrNull { pkg ->
                // After an earlier denial (this test's own, on a previous run) Android words the same button "Don't allow" with its own id.
                visibleNode(pkg) { node -> node.viewIdResourceName?.let { it.endsWith("/permission_deny_button") || it.endsWith("/permission_deny_and_dont_ask_again_button") } == true }
            }
            fun deniedNotice(): Boolean {
                var present = false
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    returned = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).asSequence()
                        .flatMap { monitor.getActivitiesInStage(it).asSequence() }.filterIsInstance<BeaconActivity>()
                        .firstOrNull { it.intent.data?.pathSegments == listOf(invitation.key, "callback") }
                    present = returned?.let { ViewModelProvider(it)[BeaconViewModel::class.java].ui.value.notice } == "Allow microphone access to call back."
                }
                return present
            }
            waitFor(15_000) { denialButton() != null || deniedNotice() }
            denialButton()?.let { check(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            waitFor(8000, ::deniedNotice)
            assertFalse(app.calls.state.value.active)
            assertNotNull(app.push.missedInvitation(source, id))
            File(out, "callback-entry.txt").writeText("System Call back action dispatched; saved event and conversation verified; " +
                "the application requested microphone permission for callback and retained the missed call when denied.")

            // C09: a call answered on the lock screen holds. The microphone is granted here; the runner restores the permission afterwards.
            val voice = dev.hermes.android.data.VoiceKeyStore(app)
            val provider = voice.settings().provider
            val previousKey = voice.key(provider)
            shell("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            voice.saveKey(provider, "fixture-voice-key")
            try {
                shell("input keyevent KEYCODE_SLEEP")
                waitFor(8000) { keyguard.isKeyguardLocked }
                val answered = ring(45)
                val answerLabels = setOf("Answer", serviceText(app, "Answer"))
                waitFor(12000) { visibleLabel(app.packageName, answerLabels) != null }
                val answerButton = checkNotNull(visibleLabel(app.packageName, answerLabels))
                check(generateSequence(answerButton) { it.parent }.first { it.isClickable && it.isEnabled }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val answerPath = "/api/plugins/beacon/mobile/callbacks/${answered.getString("event_id")}/answer"
                waitFor(10_000) { app.calls.state.value.callId == "fixture-call" }
                assertTrue(fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath })
                assertFalse("the dialer is a call window: answering is not refused (${app.calls.state.value.error})",
                    app.calls.state.value.error.contains("Open Beacon"))
                waitFor(8000) { "BeaconCallService" in shell("dumpsys activity services ${app.packageName}") }
                assertTrue("the call keeps the lock-screen dialer on top",
                    shell("dumpsys activity activities").lineSequence().any { "topResumedActivity=" in it && "IncomingDialerActivity" in it })
                assertTrue(keyguard.isKeyguardLocked)
                // The lock-screen card once the call holds (D10).
                automation.takeScreenshot()?.let { shot -> File(out, "locked-after-answer.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            } finally {
                app.calls.hangup()
                waitFor(10_000) { !app.calls.state.value.active }
                if (previousKey == null) voice.removeKey(provider) else voice.saveKey(provider, previousKey)
            }
        } finally {
            shell("cmd statusbar collapse")
            // The denial above must not leave the microphone "denied for good": the next run, and other tests, may still ask.
            shell("pm clear-permission-flags ${app.packageName} android.permission.RECORD_AUDIO user-set user-fixed")
            instrumentation.runOnMainSync { returned?.finish() }
            app.preferences.edit { if (previousOrigin == null) it.remove(originKey) else it[originKey] = previousOrigin }
            events.forEach { (source, id) ->
                app.push.discardInvitation(source, id)
                persistPushBindings(prefs, mapOf("source:${source.digest()}" to null))
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            audio.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
            if (audio.ringerMode != oldRinger) audio.ringerMode = oldRinger
            automation.serviceInfo = info.apply { flags = oldFlags }
            KeepConnectedService.clientFactoryForTest = null
            if (original.owner.isBlank() && original.bearer == null && original.cookies.isEmpty()) app.auth.remove(origin) else app.auth.write(original)
            pairPrefs.edit().putString(pairKey, oldPair).commit()
            client.closeNative()
        }
    }
}
