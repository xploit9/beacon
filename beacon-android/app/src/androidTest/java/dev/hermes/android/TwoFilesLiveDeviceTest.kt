package dev.hermes.android

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Draft
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * JOINBFIX-121 (REVIEW-JOINB-121 P2-1), live on the demo Hermes: two chats each hold a file sent while the phone had no network ("Not sent"). When the
 * network returns, the open chat's queue and the outbox send them at once, each on its own client. The plugin keeps one file socket per phone and a newer one
 * ends the older, so before the fix each file's socket ended the other's and a file ended "Not sent" on a healthy link; with one file on the way per phone both
 * reach Hermes and neither is "Not sent". Synthetic files (fileMiB each, 20 by default). Opt-in: `--arg beaconLiveService=1` with the demo sign-in staged
 * ([DemoCallSignIn]); the network is cut and restored with `svc wifi` and `svc data`. Both chats are deleted through the app's own Delete.
 */
class TwoFilesLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    private fun document(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/pdf"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/BeaconTwoFilesCheck")
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    @Test(timeout = 600_000) fun twoChatsEachHoldingAFileSentOfflineBothArriveWhenTheNetworkReturns() = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val tag = java.util.UUID.randomUUID().toString().take(6)
        val mib = InstrumentationRegistry.getArguments().getString("fileMiB")?.toIntOrNull()?.coerceIn(8, 64) ?: 20
        val chats = listOf("A", "B")
        val names = chats.associateWith { "Synthetic report $it $tag.pdf" }
        val ids = LinkedHashMap<String, String>()
        val made = mutableListOf<Uri>()
        val random = java.util.Random(121)
        try {
            withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
            val account = requireNotNull(vm.authenticatedScope()).copy(session = "")
            // 1. Two chats, each with its file attached (kept on the phone as a file, past one text frame) and some words; the second is open, the first holds its in its saved draft.
            for (chat in chats) {
                val previous = vm.chat.value.sessionId
                withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Two files $chat $tag", "request_id" to "two-files-$chat-" + java.util.UUID.randomUUID())) }.join()
                withTimeout(30_000) { while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100) }
                ids[chat] = vm.chat.value.sessionId
                withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("Synthetic file $chat $tag")) }
                val pdf = ByteArray(mib * 1024 * 1024).also(random::nextBytes)
                "%PDF-1.4\n% Beacon synthetic $chat $tag\n".toByteArray().copyInto(pdf)
                val uri = document(names.getValue(chat), pdf).also(made::add)
                withContext(Dispatchers.Main.immediate) { vm.upload(uri) }.join()
                withTimeout(60_000) { while (vm.attachments.value.none { it.string("name") == names.getValue(chat) }) delay(100) }
                assertTrue("$chat's file is kept on the phone as a file", vm.attachments.value.single { it.string("name") == names.getValue(chat) }.optBoolean("staged_file"))
            }
            status("two_files_staged", "ids=$ids file_mib=$mib open=${vm.chat.value.sessionId}")
            // 2. No network: both sends wait as "Not sent" with their files, the open chat's in its queue, the other's in its saved draft and the outbox.
            status("two_files_network_cut", listOf("svc wifi disable", "svc data disable").joinToString(" | ") { shell(it).ifBlank { "ok" } })
            delay(2_000)
            fun waiting(draft: Draft) = draft.queued.count { it.waitingForConnection() && it.attachments.isNotEmpty() }
            for (chat in listOf("B", "A")) {
                if (vm.chat.value.sessionId != ids.getValue(chat)) {
                    withContext(Dispatchers.Main.immediate) { vm.selectSession(ids.getValue(chat)) }
                    withTimeout(60_000) { while (!(vm.chat.value.sessionId == ids.getValue(chat) && vm.chat.value.projectionReady)) delay(100) }
                    withTimeout(30_000) { while (vm.attachments.value.none { it.string("name") == names.getValue(chat) }) delay(100) }
                }
                withContext(Dispatchers.Main.immediate) { vm.send() }.join()
                withTimeout(60_000) { while (vm.chat.value.queued.none { it.waitingForConnection() && it.attachments.isNotEmpty() }) delay(100) }
                status("two_files_waiting_$chat", "chat=${ids.getValue(chat)} queued=${vm.chat.value.queued.map { "${it.state}/${it.outcome}" }} connected=${vm.ui.value.connected}")
            }
            val savedB = app.cache.read(account.session(ids.getValue("B")), "draft")?.let { waiting(Draft.decode(it)) } ?: 0
            status("two_files_saved_other", "waiting_in_the_other_chats_saved_draft=$savedB")
            assertTrue("the chat that is not open holds its waiting message with its file in its saved draft", savedB == 1)
            // 3. The network returns: the open chat's queue and the outbox send both at once.
            val back = android.os.SystemClock.elapsedRealtime()
            status("two_files_network_back", listOf("svc wifi enable", "svc data enable").joinToString(" | ") { shell(it).ifBlank { "ok" } })
            val stored = HashMap<String, String>()
            withTimeoutOrNull(300_000) {
                while (stored.size < chats.size) {
                    for (chat in chats) if (chat !in stored) {
                        val row = runCatching { vm.client?.detached()?.let { c -> try { c.nativeGet("/api/sessions/${ids.getValue(chat)}/messages", mapOf("profile" to c.auth.profile)) } finally { c.closeNative() } } }.getOrNull()
                            ?.rows("messages")?.firstOrNull { it.string("role") == "user" && it.string("content", it.string("text")).contains("attachments/${names.getValue(chat)}") }
                        if (row != null) stored[chat] = row.string("content", row.string("text"))
                    }
                    if (stored.size < chats.size) delay(2_000)
                }
            }
            status("two_files_stored", "after_ms=${android.os.SystemClock.elapsedRealtime() - back} chats=${stored.keys} " + stored.entries.joinToString(" ") { (chat, text) -> "$chat='${text.take(160).replace('\n', '|')}'" })
            // 4. Neither ends "Not sent": the open chat's queue and the other chat's saved draft hold no waiting or "Not sent" message.
            withTimeoutOrNull(60_000) { while (vm.chat.value.queued.any { it.waitingForConnection() || it.fileNotSent() || it.state == "sending" }) delay(200) }
            val openLeft = vm.chat.value.queued.map { "${it.state}/${it.outcome}" }
            val openChat = ids.entries.firstOrNull { it.value == vm.chat.value.sessionId }?.key ?: "A"
            val otherChat = chats.first { it != openChat }
            val otherLeft = app.cache.read(account.session(ids.getValue(otherChat)), "draft")?.let { Draft.decode(it).queued.map { q -> "${q.state}/${q.outcome}" } }.orEmpty()
            status("two_files_after", "open_chat_queue=$openLeft other_chat_saved_queue=$otherLeft notice='${vm.ui.value.notice.take(120)}' error='${vm.ui.value.error.take(120)}'")
            assertTrue("both messages reached Hermes with their files: ${stored.keys}", stored.keys == chats.toSet() && stored.all { (chat, text) -> text.contains("attachments/${names.getValue(chat)}") })
            assertTrue("no \"Not sent\" in the open chat: $openLeft", openLeft.none { it.endsWith("/${FILE_NOT_SENT_OUTCOME}") || it.endsWith("/$OFFLINE_OUTCOME") })
            assertTrue("no \"Not sent\" in the other chat: $otherLeft", otherLeft.none { it.endsWith("/${FILE_NOT_SENT_OUTCOME}") || it.endsWith("/$OFFLINE_OUTCOME") })
        } finally { withContext(NonCancellable) {
            for (command in listOf("svc wifi enable", "svc data enable")) runCatching { shell(command) }
            // Delete refuses a chat whose reply is still running ("Stop the reply before deleting this chat."), and the demo model keeps answering a chat after the
            // test is done (the run of 18 Nov left chat B that way: its reply ran 116 s, the delete came 20 s in). So each chat is opened, its reply stopped, and
            // Delete is asked again until it is through, with the line the app gave when it was not.
            suspend fun remove(id: String): String {
                if (vm.chat.value.sessionId != id) {
                    withContext(Dispatchers.Main.immediate) { vm.selectSession(id) }
                    withTimeoutOrNull(30_000) { while (vm.chat.value.sessionId != id) delay(100) }
                }
                withTimeoutOrNull(20_000) { withContext(Dispatchers.Main.immediate) { vm.cancel() }.join() }
                withTimeoutOrNull(60_000) { while (vm.chat.value.streamId.isNotBlank() || vm.chat.value.status.isNotBlank()) delay(200) }
                var refused = ""
                repeat(12) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(id, "/api/session/delete", done = "Deleted") }.join()
                    if (vm.ui.value.notice == "Deleted") return "deleted"
                    refused = "notice='${vm.ui.value.notice.take(60)}' error='${vm.ui.value.error.take(120)}'"
                    delay(5_000)
                }
                return "NOT deleted: $refused"
            }
            val removed = ids.mapValues { (_, id) -> runCatching { remove(id) }.getOrElse { "NOT deleted: ${it.javaClass.simpleName} ${it.message?.take(100)}" } }
            made.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
            status("two_files_cleanup", "tag=$tag removed=$removed")
            assertTrue("every chat of this run was deleted: $removed", removed.values.all { it == "deleted" })
        } }
    }
}
