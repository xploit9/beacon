package dev.hermes.android

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * chat-live (owner, 26 Nov): a chat left while a reply ran was stuck where it was, then loaded the new words a moment after the return.
 * Live demo Hermes only (the staged sign-in of device-test.py --isolated). Each case opens a new synthetic chat, presses Home for longer
 * than the reply needs, returns through the real activity lifecycle, and deletes the chat. No call and no real person's chat.
 */
class ChatLiveReturnDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val resumed = AtomicReference<BeaconActivity?>()
    /** The last activity that was in front; its model is read while the activity itself is stopped in the background. */
    private val current = AtomicReference<BeaconActivity?>()
    private var scenario: ActivityScenario<BeaconActivity>? = null
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity !is BeaconActivity) return@ActivityLifecycleCallback
        if (stage == Stage.RESUMED) { current.set(activity); resumed.set(activity) } else if (stage == Stage.STOPPED && resumed.get() === activity) resumed.set(null)
    }
    private val vm get() = requireNotNull(current.get()) { "BeaconActivity has not started" }.let { activity ->
        BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }.get(activity) as BeaconViewModel
    }
    private val longPrompt = "Write a detailed guide of about nine hundred words for keeping herbs alive in a small city flat. " +
        "Use headed sections and full sentences, and explain watering, light and pots. Do not call any tools."

    @Before fun open() {
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        scenario = ActivityScenario.launch(BeaconActivity::class.java)
        waitFor("Beacon's model", 180_000) { resumed.get() != null }
    }

    @After fun close() {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        // The return uses am start, which can leave the scenario's own activity record closed; the app itself is restored by the fixture.
        runCatching { scenario?.close() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        assertTrue(what, condition())
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    private fun capture(name: String) {
        val bitmap = automation.takeScreenshot() ?: return
        val directory = File(app.filesDir, "chat-live").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /**
     * Whether any visible accessibility node holds [marker]: what the screen shows, not what the model holds.
     * Case and runs of whitespace are normalised: the transcript renders a markdown heading in caps, and a wrapped
     * paragraph breaks words across lines in the tree, so the model's own words must be compared in rendered form.
     */
    private fun shownOnScreen(marker: String): Boolean {
        fun normalise(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val needle = normalise(marker)
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val text = normalise(node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (text.contains(needle)) return true
            for (index in 0 until node.childCount) if (walk(node.getChild(index))) return true
            return false
        }
        return walk(automation.rootInActiveWindow)
    }

    /** Every visible node's words, for the failure report: what the screen holds beside the marker that was sought. */
    private fun screenText(): String = buildString {
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            val words = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
            if (words.isNotEmpty()) { if (length < 600) append(words).append(" | ") }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(automation.rootInActiveWindow)
    }

    private fun reply(chat: ChatState): String {
        val asked = chat.messages.indexOfLast { it.role == "user" }
        return chat.messages.drop(asked + 1).lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text.orEmpty()
    }

    private fun cachedReply(scope: Scope): String = runBlocking {
        val raw = app.cache.read(scope, "session") ?: return@runBlocking ""
        val session = runCatching { JSONObject(raw) }.getOrNull()?.let { it.optJSONObject("session") ?: it } ?: return@runBlocking ""
        val rows = session.rows("messages")
        val asked = rows.indexOfLast { it.string("role") == "user" }
        val answer = rows.drop(asked + 1).lastOrNull { it.string("role") == "assistant" } ?: return@runBlocking ""
        answer.string("content").ifBlank { answer.string("text") }
    }

    private suspend fun newChat(): String {
        val previous = vm.chat.value.sessionId
        withContext(Dispatchers.Main.immediate) {
            vm.newChat(json("title" to "Chat live " + UUID.randomUUID().toString().take(8), "request_id" to "chat-live-" + UUID.randomUUID(),
                "model" to "command-code/deepseek-deepseek-v4.1-flash", "model_provider" to "custom:opencodex"))
        }.join()
        withTimeout(90_000) {
            while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100)
        }
        return vm.chat.value.sessionId
    }

    private suspend fun send(text: String) {
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)); vm.send() }.join()
    }

    private fun ready() {
        waitFor("Beacon signed in", 180_000) { vm.ui.value.authenticated && vm.client != null }
        waitFor("send preferences", 30_000) { vm.preferences.value.busySendLoaded }
    }

    private fun enableKeepConnected() {
        runBlocking { withContext(Dispatchers.Main.immediate) { vm.setKeepConnectedEnabled(true) } }
        waitFor("Keep connected on", 30_000) { KeepConnectedService.isEnabled(app) }
        // The service's own start is asynchronous; its foreground importance, not its first poll, is what holds the process.
        Thread.sleep(2_000)
    }

    private fun disableKeepConnected() {
        if (KeepConnectedService.isEnabled(app)) runBlocking { withContext(Dispatchers.Main.immediate) { vm.setKeepConnectedEnabled(false) } }
        waitFor("Keep connected off", 15_000) { !KeepConnectedService.isEnabled(app) }
    }

    private fun startTurn(chat: String): Long {
        runBlocking { send(longPrompt) }
        waitFor("the turn's stream", 90_000) { vm.chat.value.sessionId == chat && vm.chat.value.streamId.isNotBlank() }
        waitFor("the first words", 120_000) { vm.chat.value.liveText.isNotBlank() || vm.chat.value.liveSealed.isNotEmpty() }
        capture("running-before-home")
        return SystemClock.elapsedRealtime()
    }

    private fun leave() {
        shell("input keyevent KEYCODE_HOME")
        waitFor("Beacon is in the background", 15_000) { resumed.get() == null }
    }

    private fun returnToBeacon() {
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        shell("am start -n dev.hermes.android.debug/dev.hermes.android.BeaconActivity")
        waitFor("Beacon is in front again", 30_000) { resumed.get() != null }
    }

    private fun delete(chat: String) {
        runCatching { runBlocking { withContext(Dispatchers.Main.immediate) { vm.conversationAction(chat, "/api/session/delete", done = "Deleted") }.join() } }
    }

    @Test(timeout = 420_000) fun aReplyThatEndedWhileBeaconWasAwayIsAlreadyOnScreenWhenItReturns() {
        ready()
        enableKeepConnected()
        val chat = runBlocking { newChat() }
        val scope = requireNotNull(vm.client).scope.session(chat)
        try {
            val awayAt = startTurn(chat)
            leave()
            val awayShotAt = SystemClock.elapsedRealtime()
            var doneAt = 0L
            var shownAway = ""
            runBlocking {
                withTimeoutOrNull(150_000) {
                    while (true) {
                        delay(250)
                        shownAway = reply(vm.chat.value)
                        if (shownAway.isNotBlank() && vm.chat.value.streamId.isBlank()) {
                            doneAt = SystemClock.elapsedRealtime(); break
                        }
                    }
                }
            }
            var savedAway = cachedReply(scope)
            val savedDeadline = SystemClock.elapsedRealtime() + 5_000
            while (savedAway.isBlank() && SystemClock.elapsedRealtime() < savedDeadline) { Thread.sleep(100); savedAway = cachedReply(scope) }
            val backAt = SystemClock.elapsedRealtime()
            // No network on the return: what shows can only be the phone's own saved chat.
            shell("svc wifi disable"); shell("svc data disable"); Thread.sleep(500)
            returnToBeacon()
            val firstAt = SystemClock.elapsedRealtime()
            val shownAtReturn = reply(vm.chat.value)
            // The follow keeps the newest words in view, so the reply's opening line can sit above the viewport:
            // any of its first, middle or last 48 characters proves the saved reply itself is painted.
            val reply = shownAway.trim()
            val markers = listOf(reply.take(48), reply.drop(reply.length / 2).take(48), reply.takeLast(48))
                .filter(String::isNotBlank).distinct()
            var visibleAt = 0L
            runBlocking {
                withTimeoutOrNull(8_000) {
                    while (true) {
                        if (markers.any(::shownOnScreen)) { visibleAt = SystemClock.elapsedRealtime(); break }
                        delay(100)
                    }
                }
            }
            capture("return-already-current")
            val shotAt = SystemClock.elapsedRealtime()
            shell("svc wifi enable"); shell("svc data enable")
            // The reply was in the chat and the phone's own cache before the return, with the app stopped; the first resumed frame is it.
            assertTrue("the reply ended while Beacon was away: shown='${shownAway.take(60)}' stream='${vm.chat.value.streamId}' status='${vm.chat.value.status}' live=${vm.chat.value.liveText.length}", shownAway.isNotBlank())
            assertTrue("the saved chat holds it too: '${savedAway.take(60)}'", savedAway.isNotBlank())
            assertEquals("the first resumed frame is the whole reply", shownAway.trim(), shownAtReturn.trim())
            assertTrue("the reply was already on screen when the activity resumed", shownAtReturn.isNotBlank())
            if (visibleAt == 0L) status("chat-live-visible", "markers=[" + markers.joinToString(" || ") + "] screen=[" + screenText().take(700) + "]")
            assertTrue("the saved reply is visible with no network", visibleAt > 0)
            status("chat-live", "awayMs=${doneAt - awayAt} awayShotMs=${awayShotAt - awayAt} resumeMs=${firstAt - backAt} visibleMs=${visibleAt - backAt} shownMs=${shotAt - backAt} replyChars=${shownAtReturn.length}")
        } finally {
            runCatching { shell("svc wifi enable"); shell("svc data enable") }
            delete(chat)
            disableKeepConnected()
        }
    }

    @Test(timeout = 420_000) fun withKeepConnectedOffTheReturnReconnectsAndShowsTheReply() {
        ready()
        disableKeepConnected()
        val chat = runBlocking { newChat() }
        val scope = requireNotNull(vm.client).scope.session(chat)
        try {
            val awayAt = startTurn(chat)
            leave()
            Thread.sleep(30_000)
            assertFalse("no background stream is kept with Keep connected off", reply(vm.chat.value).isNotBlank() && vm.chat.value.streamId.isBlank())
            val beforeReturn = cachedReply(scope)
            val backAt = SystemClock.elapsedRealtime()
            returnToBeacon()
            var firstAt = 0L
            var shownAtReturn = ""
            runBlocking {
                withTimeout(180_000) {
                    while (true) {
                        shownAtReturn = reply(vm.chat.value)
                        if (shownAtReturn.isNotBlank()) { firstAt = SystemClock.elapsedRealtime(); break }
                        delay(100)
                    }
                }
            }
            capture("return-after-reconnect")
            assertTrue("the reconnect brings the reply", shownAtReturn.isNotBlank())
            status("chat-live-off", "awayMs=${SystemClock.elapsedRealtime() - awayAt} cachedBeforeReturnChars=${beforeReturn.length} resumeToReplyMs=${firstAt - backAt} replyChars=${shownAtReturn.length}")
        } finally {
            delete(chat)
        }
    }

    /**
     * Not part of the lane's device gate: leaves a chat with its saved reply on screen for the host's force-stop proof (run by class#method).
     * The host then stops the app, relaunches it with the network off, and reads the first screen; the chat must already be there.
     */
    @Test(timeout = 360_000) fun stageAChatForTheForceStopProof() {
        ready()
        val chat = runBlocking { newChat() }
        runBlocking { send("Reply with exactly the word PINEAPPLE and nothing else.") }
        waitFor("the staged stream", 90_000) { vm.chat.value.sessionId == chat && vm.chat.value.streamId.isNotBlank() }
        waitFor("the staged reply", 180_000) { reply(vm.chat.value).isNotBlank() && vm.chat.value.streamId.isBlank() }
        val answer = reply(vm.chat.value)
        capture("force-stop-staged")
        val directory = File(app.filesDir, "chat-live").apply { mkdirs() }
        File(directory, "force-stop-staged.json").writeText(json("chat" to chat, "reply" to answer, "title" to vm.chat.value.title).toString())
        assertTrue("the staged reply: '${answer.take(60)}'", answer.contains("PINEAPPLE", ignoreCase = true))
    }

    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString(key, value) })
}
