package dev.hermes.android

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.ChatAttachmentStore
import dev.hermes.android.share.PendingShare
import dev.hermes.android.share.ShareTarget
import dev.hermes.android.share.SharedContent
import dev.hermes.android.share.SharedIntake
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * CHAT-121 follow-ups 3 and 4: documents send from a chat with session authority, now past one relay frame. Live, demo Hermes running this worktree's
 * plugin only, all files synthetic: a 26 MiB image is refused when it is picked, with Hermes' own image cap in its line, and the typed text stays; a .docx
 * with a non-Latin name comes in through the share sheet's hand-off; a 25 MiB PDF is kept on the phone as a file and sent in pieces with it, in one
 * message; Hermes' stored user message must reference both, and the bubble shows the words and two chips only. The heap is read while sending.
 * Opt-in: `--arg beaconLiveService=1` with the demo sign-in staged ([DemoCallSignIn]). The chat is deleted through the app's own Delete and the files are
 * removed from the phone; Hermes' copies in the profile's attachments/ have no delete.
 */
class DocumentSendLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })

    private fun document(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, "Download/BeaconDocumentCheck")
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test(timeout = 480_000) fun aPdfAndADocxSendInOneMessage() = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val tag = java.util.UUID.randomUUID().toString().take(6)
        val title = "Document check $tag"
        val request = "document-check-" + java.util.UUID.randomUUID()
        val pdfMime = "application/pdf"
        val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val bigName = "Synthetic photo $tag.jpg"
        val pdfName = "Synthetic report $tag.pdf"
        val docxName = "ملف تجريبي $tag.docx"
        // Synthetic only: a 25 MiB PDF of pseudo-random bytes behind a PDF header, a 26 MiB "photo" over Hermes' 25 MiB image cap, and a few .docx bytes.
        val random = java.util.Random(121)
        // pdfMiB: the PDF's size (25 by default; a small one is the control run for a long send).
        val pdfMiB = InstrumentationRegistry.getArguments().getString("pdfMiB")?.toIntOrNull()?.coerceIn(1, 64) ?: 25
        val pdf = ByteArray(pdfMiB * 1024 * 1024).also(random::nextBytes)
        "%PDF-1.4\n% Beacon synthetic $tag\n".toByteArray().copyInto(pdf)
        val big = ByteArray(26 * 1024 * 1024).also(random::nextBytes)
        val docx = "PK\u0003\u0004 synthetic docx $tag".toByteArray()
        val runtime = Runtime.getRuntime()
        status("document_check_files", "pdf=${pdf.size} sha256=${sha256(pdf)} docx=${docx.size} sha256=${sha256(docx)} big=${big.size} heap_max=${runtime.maxMemory() / (1024 * 1024)} MiB")
        val made = mutableListOf<Uri>()
        var keptCopy: java.io.File? = null
        var id = ""
        try {
            withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
            val previous = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to title, "request_id" to request)) }.join()
            withTimeout(30_000) { while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100) }
            id = vm.chat.value.sessionId
            val text = "Synthetic document check $tag"
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)) }
            // 1. Over Hermes' own image cap (25 MiB, advertised by the plugin): refused when picked, the real limit in its line, the typed text kept.
            val bigUri = document(bigName, "image/jpeg", big).also(made::add)
            withContext(Dispatchers.Main.immediate) { vm.upload(bigUri) }.join()
            val refused = vm.attachmentPreparation.value.firstOrNull { it.string("name") == bigName }
            status("document_check_refused", "row=${refused?.toString()?.take(300)} attachments=${vm.attachments.value.size} composer='${vm.composer.value.text}'")
            assertEquals("error", refused?.string("status"))
            assertEquals("Too large to send to this Hermes (26 MB at most).", refused?.string("error"))
            assertEquals(text, vm.composer.value.text)
            assertTrue(vm.attachments.value.none { it.string("name") == bigName })
            withContext(Dispatchers.Main.immediate) { vm.dismissAttachmentPreparation(refused!!.string("id")) }
            // 2. The .docx through the share sheet's hand-off into this chat (the chooser offers it; the app claims it and attaches it).
            val docxUri = document(docxName, docxMime, docx).also(made::add)
            SharedIntake.offer(PendingShare(ShareTarget.Chat(id), SharedContent("", listOf(docxUri.toString()), onlyImages = false), System.currentTimeMillis()))
            withTimeout(60_000) { while (vm.attachments.value.none { it.string("name") == docxName }) delay(100) }
            // 3. The 25 MiB PDF through the attach button's path: kept on the phone as a file, no base64 in the draft.
            val pdfUri = document(pdfName, pdfMime, pdf).also(made::add)
            withContext(Dispatchers.Main.immediate) { vm.upload(pdfUri) }.join()
            withTimeout(60_000) { while (vm.attachments.value.size < 2) delay(100) }
            val keptItem = vm.attachments.value.single { it.string("name") == pdfName }
            keptCopy = ChatAttachmentStore.file(keptItem)
            status("document_check_attached", "names=${vm.attachments.value.map { it.string("name") }} kept=${keptItem.optBoolean("staged_file")} " +
                "base64=${keptItem.string("staged_base64").length} kept_bytes=${ChatAttachmentStore.file(keptItem)?.length()} prep=${vm.attachmentPreparation.value.size}")
            // Past one text frame the file is kept on the phone (no base64); a small one goes as base64, as before.
            if (pdfMiB >= 7) assertTrue(keptItem.optBoolean("staged_file") && keptItem.string("staged_base64").isEmpty() && ChatAttachmentStore.file(keptItem)?.length() == pdf.size.toLong())
            else assertTrue(!keptItem.optBoolean("staged_file") && keptItem.string("staged_base64").isNotEmpty())
            // The heap while sending (the 25 MiB file goes in pieces from its kept copy): read every 50 ms.
            repeat(2) { System.gc(); delay(100) }
            val heapBase = runtime.totalMemory() - runtime.freeMemory()
            var heapPeak = heapBase
            val sampler = launch(Dispatchers.Default) { while (isActive) { heapPeak = maxOf(heapPeak, runtime.totalMemory() - runtime.freeMemory()); delay(50) } }
            val sendBegan = android.os.SystemClock.elapsedRealtime()
            try { withContext(Dispatchers.Main.immediate) { vm.send() }.join() } finally { sampler.cancel() }
            status("document_check_heap", "base=${heapBase / (1024 * 1024)} MiB peak_over_base=${(heapPeak - heapBase) / (1024 * 1024)} MiB max=${runtime.maxMemory() / (1024 * 1024)} MiB " +
                "send_ms=${android.os.SystemClock.elapsedRealtime() - sendBegan}")
            val sendNotice = vm.ui.value.notice
            // What Hermes stored: the session's own user message, read back from Hermes.
            val stored = withTimeoutOrNull(60_000) {
                var found: org.json.JSONObject? = null
                while (found == null) {
                    found = runCatching { vm.client?.detached()?.let { c -> try { c.nativeGet("/api/sessions/$id/messages", mapOf("profile" to c.auth.profile)) } finally { c.closeNative() } } }.getOrNull()
                        ?.rows("messages")?.firstOrNull { it.string("role") == "user" && it.string("content", it.string("text")).contains(tag) }
                    if (found == null) delay(1_000)
                }
                found
            }
            val content = stored?.let { it.string("content", it.string("text")) }.orEmpty()
            status("document_check_stored", "id=$id notice='${sendNotice.take(160)}' content='${content.take(600).replace('\n', '|')}'")
            assertTrue("Hermes stored no user message for the send (notice '$sendNotice')", stored != null)
            assertTrue("the PDF is referenced: $content", content.contains("attachments/$pdfName"))
            assertTrue("the .docx is referenced with its name: $content", content.contains("attachments/$docxName"))
            // One user row on the phone once the send reconciles with Hermes' row.
            // What the phone shows after the send, every 2 s for up to 30 s (where it stands if the row never comes).
            val watched = launch {
                val began = android.os.SystemClock.elapsedRealtime()
                while (isActive) {
                    val chat = vm.chat.value; val ui = vm.ui.value
                    status("document_check_after_${(android.os.SystemClock.elapsedRealtime() - began) / 1000}", "sid=${chat.sessionId.take(16)} status='${chat.status}' uncertain=${chat.uncertain.take(12)} " +
                        "stream=${chat.streamId.take(12)} msgs=${chat.messages.map { it.role }} queued=${chat.queued.size} connected=${ui.connected} tab=${ui.tab} error='${ui.error.take(120)}' notice='${ui.notice.take(80)}'")
                    delay(2_000)
                }
            }
            val users = withTimeoutOrNull(30_000) {
                while (vm.chat.value.messages.count { it.role == "user" } != 1 || vm.chat.value.uncertain.isNotBlank()) delay(200)
                vm.chat.value.messages.count { it.role == "user" }
            }
            watched.cancel()
            status("document_check_rows", "users=$users all=${vm.chat.value.messages.map { it.role }}")
            assertEquals(1, users)
            // Hermes admitted the send (its row is shown), so it holds the file: the copy kept on this phone is gone (REVIEW-121B F2-P3-5, follow-up 6).
            if (pdfMiB >= 7) assertTrue("the kept copy goes once Hermes admits the send",
                withTimeoutOrNull(5_000) { while (keptCopy?.exists() == true) delay(100); true } == true)
            // The bubble, as ChatScreen presents Hermes' row: the typed words and one chip a file, without Hermes' "Attached Context" (host paths).
            val row = vm.chat.value.messages.first { it.role == "user" }
            val shown = dev.hermes.android.ui.safeHistoryMessagePresentation("user", dev.hermes.android.ui.splitAttachedMedia(row.text).text)
            status("document_check_shown", "text='${shown.text.replace('\n', '|')}' chips=${shown.attachments.map { it.name }} raw_has_context=${row.text.contains("--- Attached Context ---")}")
            assertEquals(text, shown.text)
            assertEquals(listOf(docxName, pdfName), shown.attachments.map { it.name })
        } finally { withContext(NonCancellable) {
            // The demo model may still be answering: stop it, then delete the chat through the conversation menu's own Delete.
            runCatching { withTimeoutOrNull(15_000) { withContext(Dispatchers.Main.immediate) { vm.cancel() }.join() } }
            val chat = id.ifBlank { runCatching { withTimeoutOrNull(20_000) { vm.client?.detached()?.let { c ->
                try { c.post("/api/session/new", json("title" to title, "request_id" to request)).let { (it.optJSONObject("session") ?: it).getString("session_id") } }
                finally { c.closeNative() }
            } } }.getOrNull().orEmpty() }
            val deleted = chat.isNotBlank() && runCatching { withTimeoutOrNull(30_000) {
                var ok = false
                repeat(3) { if (!ok) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(chat, "/api/session/delete", done = "Deleted") }.join()
                    ok = vm.ui.value.notice == "Deleted"
                    if (!ok) delay(3_000)
                } }
                ok
            } == true }.getOrDefault(false)
            made.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
            val keptLeft = keptCopy?.exists() == true
            keptCopy?.delete()
            status("document_check_cleanup", "title='$title' id=$chat deleted=$deleted kept_copy_left_after_send=$keptLeft notice='${vm.ui.value.notice.take(120)}'")
        } }
    }
}
