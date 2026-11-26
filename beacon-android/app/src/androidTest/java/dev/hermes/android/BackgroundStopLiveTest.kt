package dev.hermes.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.ui.BackgroundActivityItem
import dev.hermes.android.ui.backgroundActivityRuntime
import dev.hermes.android.ui.fetchBackgroundActivity
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The owner's 26 Nov report, live: the Background activity sheet can stop a running process through Hermes' own
 * `process.kill` and only claims it stopped when Hermes' receipt says the process exited. Opt-in:
 * `device-test.py --isolated --arg beaconLiveService=1` with the demo sign-in staged ([DemoCallSignIn]); the synthetic
 * chat runs the demo's working DeepSeek route on its own session and asks the agent to start `sleep 300` in the background.
 */
class BackgroundStopLiveTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun status(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString(key, value) })
    }

    /** Full-screen proof, dialogs included: the Compose root would capture only one window. */
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = app.getExternalFilesDir("background-stop") ?: app.filesDir
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test(timeout = 360_000) fun aRunningProcessStopsFromTheSheetAndHermesAgrees() = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1",
            InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withTimeout(120_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
        val tag = UUID.randomUUID().toString().take(6)
        val title = "Background stop $tag"
        val previous = vm.chat.value.sessionId
        withContext(Dispatchers.Main.immediate) {
            vm.newChat(json("title" to title, "request_id" to "background-stop-$tag",
                "model" to "command-code/deepseek/deepseek-v4.1-flash", "model_provider" to "opencodex"))
        }.join()
        withTimeout(60_000) { while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100) }
        val session = vm.chat.value.sessionId
        val profile = vm.ui.value.profile
        val client = requireNotNull(vm.client)
        status("background_stop_session", session)
        val prompt = "Run this exact shell command as a background process with the terminal tool, background=true: sleep 300. " +
            "Do not run it in the foreground. Do not wait on it. Reply with the process id only."
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(prompt)); vm.send() }.join()
        var item: BackgroundActivityItem? = null
        withTimeout(180_000) {
            while (item == null) {
                if (vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank()) {
                    item = fetchBackgroundActivity(client, profile, session).items.firstOrNull {
                        it.id.startsWith("process:") && it.title.contains("sleep 300") && it.running
                    }
                }
                if (item == null) delay(2_000)
            }
        }
        val target = requireNotNull(item)
        status("background_stop_process", target.id)
        // The agent's own reply names the host pid it started; the canonical process list does not export one.
        val reportedPid = Regex("pid (\\d+)").find(vm.chat.value.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        status("background_stop_reported_pid", reportedPid.toString())
        // The turn that started it must be over before the composer area (and its strip) shows.
        withTimeout(60_000) { while (vm.chat.value.streamId.isNotBlank()) delay(200) }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("chat-activity-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-activity-strip").performClick()
        compose.onNodeWithTag("chat-activity-sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat-activity-stop-${target.id}").performScrollTo().assertIsDisplayed()
        capture("background-stop-running")
        compose.onNodeWithTag("chat-activity-stop-${target.id}").performClick()
        compose.onNodeWithText("Stop this background process?").assertIsDisplayed()
        capture("background-stop-confirm")
        compose.onNodeWithTag("chat-activity-stop-confirm").performClick()
        withTimeout(90_000) {
            while (true) {
                val current = fetchBackgroundActivity(client, profile, session)
                val row = current.items.firstOrNull { it.id == target.id }
                if (row == null || !row.running) break
                delay(1_000)
            }
        }
        val inventory = client.nativeRpc("session.active_list", JSONObject())
        val runtime = requireNotNull(backgroundActivityRuntime(inventory, session))
        val receipt = requireNotNull(client.nativeRpc("process.list", json("session_id" to runtime, "profile" to profile))
            .rows("processes").firstOrNull { it.string("session_id") == target.id.removePrefix("process:") }
        ) { "Hermes still lists the stopped process briefly" }
        assertEquals("exited", receipt.string("status"))
        assertEquals("killed", receipt.string("completion_reason"))
        val pid = receipt.optInt("pid")
        status("background_stop_pid", pid.toString())
        status("background_stop_receipt", receipt.toString().take(1200))
        // The stopped row is still in the reconciled sheet with an honest terminal status.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Stopped", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("No longer listed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the row is no longer offered a second stop", compose.onAllNodesWithTag("chat-activity-stop-${target.id}").fetchSemanticsNodes().isEmpty())
        delay(800) // the confirmation's exit animation must finish before the stopped proof is captured
        capture("background-stop-stopped")
        // The exact host PID Hermes reported must be gone; the local runner checks it after the device run.
        status("background_stop_stopped", "true")
    }
}
