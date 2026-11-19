package dev.hermes.android

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * CHAT-121: a New chat asked the moment a cold start reads connected, before the start reopens the chat the app had open. Selecting that
 * restored chat over it made the new chat on Hermes and never opened it (device: 4 of 4 at 36c3959c). The new chat must open.
 * Opt-in, demo Hermes only: `device-test.py ... --isolated --arg beaconLiveService=1` (staged demo sign-in, [DemoCallSignIn]).
 * It leaves nothing on Hermes: its create carries a request id (Hermes' retry identity), so at the end Hermes is asked for the chat that
 * id made (it makes it now if the first create never arrived) and that chat is deleted through the app's own Delete.
 */
class NewChatOnConnectDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Test(timeout = 300_000) fun aNewChatAskedAsTheAppConnectsOpens() = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        var previous = ""; var asked = false
        val title = "New chat on connect " + java.util.UUID.randomUUID().toString().take(6)
        val request = "new-chat-on-connect-" + java.util.UUID.randomUUID()
        val trigger = CompletableDeferred<Unit>()
        // On the main thread, in the same turn the app publishes connected: before the start goes on to reopen its saved chat.
        val watcher = launch(Dispatchers.Main.immediate) {
            vm.ui.collect { u ->
                if (!asked && u.connected && vm.client != null && !u.restoringSession) {
                    asked = true
                    previous = vm.chat.value.sessionId
                    vm.newChat(json("title" to title, "request_id" to request))
                    trigger.complete(Unit)
                }
            }
        }
        try {
            withTimeout(90_000) { trigger.await() }
            watcher.cancel()
            val opened = withTimeoutOrNull(30_000) {
                while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) {
                    runCatching { rule.mainClock.advanceTimeByFrame() }; delay(100)
                }
                true
            } == true
            val u = vm.ui.value
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("new_chat_on_connect", "opened=$opened previous=${previous.take(14)} now=${vm.chat.value.sessionId.take(14)} newChatError='${u.newChatError}' error='${u.error.take(120)}'")
            })
            assertTrue("The new chat asked as the app connected did not open (still ${vm.chat.value.sessionId.take(14)}, error '${u.error.take(120)}')", opened)
        } finally { withContext(NonCancellable) {
            watcher.cancel()
            // The id from Hermes' own answer, never from the chat on screen (which may be another one when the new chat did not open).
            val made = runCatching { withTimeoutOrNull(20_000) { vm.client?.detached()?.let { c ->
                try { c.post("/api/session/new", json("title" to title, "request_id" to request)).let { (it.optJSONObject("session") ?: it).getString("session_id") } }
                finally { c.closeNative() }
            } } }
            val id = made.getOrNull().orEmpty()
            // The conversation menu's own Delete (ConversationMenu): it says "Deleted" only after Hermes confirmed the deletion.
            val deleted = id.isNotBlank() && runCatching { withTimeoutOrNull(20_000) {
                withContext(Dispatchers.Main.immediate) { vm.conversationAction(id, "/api/session/delete", done = "Deleted") }.join()
                vm.ui.value.notice == "Deleted"
            } == true }.getOrDefault(false)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("new_chat_on_connect_cleanup", "title='$title' id=${id.take(14)} deleted=$deleted error='${made.exceptionOrNull()?.message.orEmpty().take(120)}'")
            })
        } }
    }
}
