package dev.hermes.android

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.HermesReach
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * CHAT-121 follow-up 9, live on the demo Hermes (its plugin from this worktree), all files synthetic: a file goes on a socket of its own and never takes the
 * chat's socket down, on a slow upload link. The link is the host's fu9_link_proxy.py (the emulator's global http_proxy for the run): one queue for every
 * byte the phone sends, at the rate the test sets on its control port (10.0.2.2:8896), as a phone's radio buffer is; this API 36 emulator's own
 * `emu network speed` shapes nothing (fu9-throttle-probe*.json). Each case runs alone (its own sign-in): `--arg beaconLiveService=1` with the demo sign-in
 * staged ([DemoCallSignIn]). Its chats are deleted through the app's own Delete, and the files removed from the phone. The host records the screen
 * (fu9_live_run.py: screenrecord started before each case and stopped with SIGINT after it, so the file is finished).
 * Follow-up 10 adds an image on the file's socket, the call's warm-up on a slow link, and a call's start on a normal link (fu10_live_run.py).
 */
class FileLinkLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })

    /** The link proxy's control: one line, one JSON line back (a raw socket: the device's http_proxy is for the app's own connections). */
    private suspend fun link(line: String): JSONObject = withContext(Dispatchers.IO) {
        java.net.Socket("10.0.2.2", 8896).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply { write((line + "\n").toByteArray()); flush() }
            JSONObject(socket.getInputStream().bufferedReader().readLine())
        }
    }

    private fun document(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, "Download/BeaconLinkCheck")
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    /** The proxy's tunnels open at or after this case began ([since]), and its clock. */
    private suspend fun proxyStats() = link("stats $since")
    /** The proxy's clock. */
    private suspend fun proxyNow() = link("now").optDouble("t")

    private fun synthetic(size: Int, seed: Long, tag: String) = ByteArray(size).also { java.util.Random(seed).nextBytes(it); "%PDF-1.4\n% Beacon link check $tag\n".toByteArray().copyInto(it) }
    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The chat's own socket as the app holds it (its client's NativeGateway connection): its identity, 0 while there is none. */
    private fun chatSocket(vm: BeaconViewModel): Int = runCatching {
        val client = vm.client ?: return 0
        val native = BeaconClient::class.java.getDeclaredField("native").apply { isAccessible = true }.get(client) ?: return 0
        System.identityHashCode(native.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(native) ?: return 0)
    }.getOrDefault(0)

    /** What the app showed while it ran, every 250 ms: the chat sockets it held, samples with none, and samples with its offline banner up. */
    private class Watch {
        val sockets = linkedSetOf<Int>(); var samples = 0; var none = 0; var banner = 0; var restoring = 0; val tabs = linkedSetOf<String>(); val changes = mutableListOf<String>()
        /** Samples with the chat's reply status at "Reconnecting…" (its stream lost while Hermes still works on the reply), and the call warm-up's steps. */
        var reconnecting = 0; val prewarm = mutableListOf<String>()
        /** Each live session the warm-up held while this ran (CallPrewarm's own), in order. */
        val sessions = linkedSetOf<dev.hermes.android.voice.LiveSession>()
        /** Each context the warm-up read, as the setup it would send for it: when, its sha256 (12 characters), its size and its parts' sizes. */
        var heldContext: Any? = null; val setups = mutableListOf<String>()
    }
    private fun CoroutineScope.watch(vm: BeaconViewModel, seen: Watch) = launch(Dispatchers.Default) {
        val began = SystemClock.elapsedRealtime()
        var last = -1
        var warm = ""
        while (isActive) {
            val ui = vm.ui.value
            seen.samples++
            if (vm.chat.value.status == "Reconnecting…") seen.reconnecting++
            val step = app.calls.prewarm.status
            if (step != warm && seen.prewarm.size < 60) seen.prewarm += "${SystemClock.elapsedRealtime() - began}ms:$step"
            warm = step
            warmSession()?.let { seen.sessions += it }
            heldContext()?.takeIf { it !== seen.heldContext }?.let { held ->
                seen.heldContext = held
                if (seen.setups.size < 40) setupLine(held)?.let { seen.setups += "${SystemClock.elapsedRealtime() - began}ms:$it" }
            }
            if (!ui.connected || ui.origin in HermesReach.origins.value) seen.banner++
            if (ui.restoringSession) seen.restoring++
            seen.tabs += ui.tab
            val socket = chatSocket(vm)
            if (socket == 0) seen.none++ else seen.sockets += socket
            // When the chat's socket changed, and to what (0: none), for the report.
            if (socket != last && seen.changes.size < 40) seen.changes += "${SystemClock.elapsedRealtime() - began}ms:${if (socket == 0) "none" else "s" + seen.sockets.indexOf(socket)}"
            last = socket
            delay(250)
        }
    }
    /** The live session the call's warm-up holds now (CallPrewarm's own field), or null. */
    private fun warmSession(): dev.hermes.android.voice.LiveSession? = runCatching {
        val prewarm = app.calls.prewarm
        prewarm.javaClass.getDeclaredField("warm").apply { isAccessible = true }.get(prewarm) as? dev.hermes.android.voice.LiveSession
    }.getOrNull()
    /** The context the call's warm-up holds now (CallPrewarm's own field), or null. */
    private fun heldContext(): dev.hermes.android.voice.PrefetchedContext? = runCatching {
        val prewarm = app.calls.prewarm
        prewarm.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(prewarm) as? dev.hermes.android.voice.PrefetchedContext
    }.getOrNull()
    /** The setup a warm-up would send for [held] (no key in it): sha256 (12), bytes, and the instruction's and the tool declarations' bytes. */
    private fun setupLine(held: dev.hermes.android.voice.PrefetchedContext): String? = runCatching {
        val credentials = VoiceKeyStore(app).session() ?: return null
        val setup = dev.hermes.android.voice.liveSetup(credentials, held.value).getJSONObject("setup")
        val whole = JSONObject().put("setup", setup).toString().toByteArray()
        val instruction = setup.optJSONObject("systemInstruction")?.toString()?.toByteArray()?.size ?: 0
        val tools = setup.optJSONArray("tools")?.toString()?.toByteArray()?.size ?: 0
        "${sha256(whole).take(12)}/${whole.size}(instruction=$instruction,tools=$tools)"
    }.getOrNull()
    /** Each warm session seen: how long its socket took to open, then how long Google took to answer its setup (none: never), and whether it ended. */
    private fun Watch.sessionsLine() = "sessions=" + sessions.joinToString(",", "[", "]") {
        "{open_ms=${if (it.socketOpenAt < 0) "none" else (it.socketOpenAt - it.openedAt).toString()} " +
            "setup_ms=${if (it.setupAt < 0) "none" else (it.setupAt - it.socketOpenAt).toString()} ended=${it.dead}}"
    }
    private fun Watch.line() = "chat_sockets=${sockets.size} without_socket=$none banner=$banner restoring=$restoring reconnecting=$reconnecting tabs=$tabs " +
        "samples=$samples changes=${changes.joinToString(",")}"

    /**
     * The proxy's tunnels to Hermes (the demo's port 8446) opened at or after [since] (its clock): how many there were, how many ended, and which carried a
     * file (more than [fileBytes] up). The device's own traffic goes through the proxy too (Google's services, Gemini Live's warm-up), and is left out.
     */
    private fun hermesTunnels(stats: JSONObject, since: Double) = stats.rows("tunnels").filter { it.optDouble("opened") >= since && it.string("target").endsWith(":8446") }
    private fun tunnels(stats: JSONObject, fileBytes: Int, since: Double): String {
        val all = hermesTunnels(stats, since)
        val files = all.filter { it.optLong("up") > fileBytes }
        return "tunnels=${all.size} closed=${all.count { !it.isNull("closed") }} file_tunnels=${files.size} cut=${all.count { it.optBoolean("cut") }} " +
            "non_file_closed=${all.count { !it.isNull("closed") && it.optLong("up") <= fileBytes }} longest_queue_wait_s=${stats.optDouble("longest_wait")}"
    }

    /** Who ended a tunnel first, as the proxy saw it: the server, the phone, the proxy's cut, or nobody yet. */
    private fun closer(t: JSONObject) = when {
        t.isNull("closed") -> "open"
        t.optBoolean("cut") -> "cut"
        !t.isNull("server_closed") && (t.isNull("phone_closed") || t.optDouble("server_closed") <= t.optDouble("phone_closed")) -> "server"
        else -> "phone"
    }
    private fun at(value: Double, since: Double) = String.format(Locale.ROOT, "%.1f", value - since)
    /** Tunnels to [host] that were open at some point since [since] (the proxy's clock). */
    private fun during(stats: JSONObject, host: String, since: Double) = stats.rows("tunnels").filter { it.string("target").startsWith(host) && (it.isNull("closed") || it.optDouble("closed") >= since) }
    /**
     * Gemini Live's sockets since [since]: the call's warm-up (VoicePrewarm) opens one to Google and sends the call's setup on it. Each: when it opened,
     * the bytes the phone sent on it and how many got through the link, the longest its bytes waited in the queue, and when and by whom it ended.
     */
    private fun warmups(stats: JSONObject, since: Double): String {
        val live = during(stats, "generativelanguage.googleapis.com:", since).filter { it.optDouble("opened") >= since }
        return "warmups=${live.size} up_total=${live.sumOf { it.optLong("up") }} " + live.joinToString(",", "[", "]") { t ->
            "{at=${at(t.optDouble("opened"), since)} up=${t.optLong("up")} sent=${t.optLong("sent")} wait=${t.optDouble("max_wait")} " +
                "ended=${if (t.isNull("closed")) "-" else at(t.optDouble("closed"), since)} by=${closer(t)}}"
        }
    }
    /**
     * Hermes' tunnels open at some point since [since]: the longest a pong-sized record (128 bytes or less) and any byte of theirs waited in the queue, and
     * how many the server ended (the dashboard closes a socket whose pong is 20 s late) and how many the phone did.
     */
    private fun hermesWaits(stats: JSONObject, since: Double): String {
        val all = during(stats, "", since).filter { it.string("target").endsWith(":8446") }
        return "hermes_tunnels=${all.size} pong_sized_longest_wait_s=${all.maxOfOrNull { it.optDouble("small_max_wait") } ?: 0.0} " +
            "longest_wait_s=${all.maxOfOrNull { it.optDouble("max_wait") } ?: 0.0} server_ended=${all.count { closer(it) == "server" }} phone_ended=${all.count { closer(it) == "phone" }}"
    }

    private suspend fun ready(vm: BeaconViewModel) = withTimeout(120_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }

    /**
     * The model each test chat answers with, when the run names one (`beaconModel`, `beaconModelProvider`: a demo whose default model cannot answer now);
     * per chat, the demo's own config untouched. Without them the chat takes the demo's default.
     */
    private fun route(body: JSONObject) = body.apply {
        val args = InstrumentationRegistry.getArguments()
        args.getString("beaconModel")?.takeIf(String::isNotBlank)?.let { put("model", it) }
        args.getString("beaconModelProvider")?.takeIf(String::isNotBlank)?.let { put("model_provider", it) }
    }

    private suspend fun newChat(vm: BeaconViewModel, title: String): String {
        val previous = vm.chat.value.sessionId
        withContext(Dispatchers.Main.immediate) { vm.newChat(route(json("title" to title, "request_id" to "link-check-" + UUID.randomUUID()))) }.join()
        withTimeout(60_000) { while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100) }
        return vm.chat.value.sessionId
    }

    private suspend fun attach(vm: BeaconViewModel, text: String, uri: Uri, name: String) {
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)) }
        withContext(Dispatchers.Main.immediate) { vm.upload(uri) }.join()
        withTimeout(120_000) { while (vm.attachments.value.none { it.string("name") == name }) delay(100) }
    }

    /** Hermes' own record of the user message with [marker]: its content, or "" when none came in [timeoutMs]. */
    private suspend fun stored(vm: BeaconViewModel, id: String, marker: String, timeoutMs: Long = 60_000): String = withTimeoutOrNull(timeoutMs) {
        while (true) {
            runCatching { vm.client?.detached()?.let { c -> try { c.nativeGet("/api/sessions/$id/messages", mapOf("profile" to c.auth.profile)) } finally { c.closeNative() } } }.getOrNull()
                ?.rows("messages")?.firstOrNull { it.string("role") == "user" && it.string("content", it.string("text")).contains(marker) }
                ?.let { return@withTimeoutOrNull it.string("content", it.string("text")) }
            delay(1_000)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }.orEmpty()

    /** The reply to the last user message: its first words, or "" when none came in [timeoutMs]. */
    private suspend fun reply(vm: BeaconViewModel, timeoutMs: Long = 180_000): String = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val chat = vm.chat.value
            val users = chat.messages.indexOfLast { it.role == "user" }
            val answer = chat.messages.drop(users + 1).firstOrNull { it.role == "assistant" && it.text.isNotBlank() }
            if (users >= 0 && answer != null && chat.streamId.isBlank()) return@withTimeoutOrNull answer.text.take(80).replace('\n', ' ')
            delay(500)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }.orEmpty()

    /** The reply to the user message holding [marker] (not an earlier one's, while that message still waits to go): its first words, or "" when none came. */
    private suspend fun replyTo(vm: BeaconViewModel, marker: String, timeoutMs: Long = 300_000): String = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val chat = vm.chat.value
            val asked = chat.messages.indexOfLast { it.role == "user" && it.text.contains(marker) }
            val answer = if (asked < 0) null else chat.messages.drop(asked + 1).firstOrNull { it.role == "assistant" && it.text.isNotBlank() }
            if (answer != null && chat.streamId.isBlank()) return@withTimeoutOrNull answer.text.take(80).replace('\n', ' ')
            delay(500)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }.orEmpty()

    /** The proxy's clock when this case began: its tunnels are the ones opened since. */
    private var since = 0.0

    private fun live(body: suspend CoroutineScope.(vm: BeaconViewModel, tag: String, made: MutableList<Uri>, chats: MutableList<String>) -> Unit) = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val tag = UUID.randomUUID().toString().take(6)
        val made = mutableListOf<Uri>()
        val chats = mutableListOf<String>()
        try {
            link("cut 0"); link("rate 0")
            since = proxyNow()
            ready(vm)
            // The chat on screen, for the host's recording. A Compose test draws a new frame only when the test's clock moves (it waits for idle or reads a
            // node), so the screen would stay on its first frame: the clock moves here 4 times a second, as it would on a phone.
            withContext(Dispatchers.Main.immediate) { if (vm.ui.value.tab != "Chat") vm.tab("Chat") }
            val frames = launch(Dispatchers.Default) { while (isActive) { runCatching { rule.mainClock.advanceTimeBy(250) }; delay(250) } }
            try { this.body(vm, tag, made, chats) } finally { frames.cancel() }
        } finally { withContext(NonCancellable) {
            runCatching { link("cut 0"); link("rate 0") }
            runCatching { withTimeoutOrNull(15_000) { withContext(Dispatchers.Main.immediate) { vm.cancel() }.join() } }
            val deleted = chats.map { chat -> chat to (runCatching { withTimeoutOrNull(40_000) {
                var ok = false
                repeat(3) { if (!ok) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(chat, "/api/session/delete", done = "Deleted") }.join()
                    ok = vm.ui.value.notice == "Deleted"
                    if (!ok) delay(3_000)
                } }
                ok
            } == true }.getOrDefault(false)) }
            made.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
            status("link_check_cleanup", "tag=$tag deleted=$deleted")
        } }
    }

    /**
     * a) Hermes gets each file whole and answers: about 256 KiB on a 2 KB/s link, then 1 MiB on a 10 KB/s link, each in a message of its own. The host checks
     * the bytes Hermes staged against the sha256 printed here.
     */
    @Test(timeout = 1_200_000) fun aFileArrivesWholeOnASlowLinkAndTheReplyComes() = live { vm, tag, made, chats ->
        val id = newChat(vm, "Link check A $tag").also(chats::add)
        for ((step, case) in listOf(Triple(2000, 256 * 1024, "ملف تجريبي $tag.pdf"), Triple(10_000, 1024 * 1024, "Synthetic report $tag.pdf")).withIndex()) {
            val (rate, size, name) = case
            val bytes = synthetic(size, 900L + step, tag)
            val text = "Synthetic link check $tag, step ${step + 1}. Reply with one short sentence."
            attach(vm, text, document(name, "application/pdf", bytes).also(made::add), name)
            link("rate $rate")
            val seen = Watch()
            val watcher = watch(vm, seen)
            val began = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main.immediate) { vm.send() }.join()
            val sendMs = SystemClock.elapsedRealtime() - began
            link("rate 0")
            watcher.cancel()
            val stats = proxyStats()
            val content = stored(vm, id, "step ${step + 1}.")
            status("link_check_a${step + 1}", "rate=$rate size=$size sha256=${sha256(bytes)} name='$name' send_ms=$sendMs notice='${vm.ui.value.notice.take(100)}' ${seen.line()} ${tunnels(stats, size, since)}")
            status("link_check_a${step + 1}_stored", "id=$id content='${content.take(300).replace('\n', '|')}'")
            assertTrue("Hermes holds the message with the file: $content", content.contains("attachments/$name"))
            assertEquals("the chat kept one socket: ${seen.line()}", 1, seen.sockets.size)
            assertEquals("no offline banner: ${seen.line()}", 0, seen.banner)
            val answer = reply(vm)
            status("link_check_a${step + 1}_reply", "reply='$answer'")
            assertTrue("the reply comes", answer.isNotBlank())
        }
    }

    /** b) The link slows from 10 KB/s to 2 KB/s 20 s into a 256 KiB file (about 340 KB of base64): it finishes, and the chat keeps its socket. */
    @Test(timeout = 900_000) fun aFileFinishesWhenTheLinkSlowsDownMidFile() = live { vm, tag, made, chats ->
        val id = newChat(vm, "Link check B $tag").also(chats::add)
        val size = 256 * 1024
        val name = "Synthetic slowdown $tag.pdf"
        val bytes = synthetic(size, 910L, tag)
        val text = "Synthetic link check $tag, slowdown. Reply with one short sentence."
        attach(vm, text, document(name, "application/pdf", bytes).also(made::add), name)
        link("rate 10000")
        val seen = Watch()
        val watcher = watch(vm, seen)
        val began = SystemClock.elapsedRealtime()
        val sending = withContext(Dispatchers.Main.immediate) { vm.send() }
        delay(20_000)
        link("rate 2000")
        sending.join()
        val sendMs = SystemClock.elapsedRealtime() - began
        link("rate 0")
        watcher.cancel()
        val stats = proxyStats()
        val content = stored(vm, id, "slowdown.")
        status("link_check_b", "size=$size sha256=${sha256(bytes)} name='$name' send_ms=$sendMs notice='${vm.ui.value.notice.take(100)}' ${seen.line()} ${tunnels(stats, size / 2, since)}")
        status("link_check_b_stored", "id=$id content='${content.take(300).replace('\n', '|')}'")
        assertTrue("Hermes holds the message with the file: $content", content.contains("attachments/$name"))
        assertEquals("the chat kept one socket: ${seen.line()}", 1, seen.sockets.size)
        assertEquals(0, seen.banner)
        val answer = reply(vm)
        status("link_check_b_reply", "reply='$answer'")
        assertTrue("the reply comes", answer.isNotBlank())
    }

    /**
     * c) A 0.5 KB/s link while another chat's reply streams (on a socket of its own, as the app streams a reply): the file goes a small piece at a time on its
     * own socket, the other chat's reply keeps coming, the chat keeps its one socket and the app never shows its offline banner.
     */
    @Test(timeout = 1_200_000) fun onAHalfKilobyteLinkAFileNeverTakesTheChatDownWhileAnotherChatStreams() = live { vm, tag, made, chats ->
        // The other chat, made and run on a client of its own (the app streams a reply on one), so the open chat stays the file's.
        val streamer = requireNotNull(vm.client).detached()
        val other = streamer.post("/api/session/new", route(json("title" to "Link check C other $tag", "request_id" to "link-check-" + UUID.randomUUID())))
            .let { (it.optJSONObject("session") ?: it).getString("session_id") }.also(chats::add)
        val id = newChat(vm, "Link check C $tag").also(chats::add)
        val size = 48 * 1024
        val name = "Synthetic half-kilobyte $tag.pdf"
        val bytes = synthetic(size, 920L, tag)
        val text = "Synthetic link check $tag, half kilobyte. Reply with one short sentence."
        attach(vm, text, document(name, "application/pdf", bytes).also(made::add), name)
        val tokens = java.util.Collections.synchronizedList(mutableListOf<Long>())
        var ended = ""
        val turn = streamer.post("/api/chat/start", json("session_id" to other, "client_request_id" to "link-check-" + UUID.randomUUID(),
            "message" to "Write about 500 words on how rivers shape valleys, in plain sentences. $tag"))
        val streaming = launch(Dispatchers.IO) {
            runCatching { streamer.stream("/api/chat/stream", mapOf("session_id" to other, "stream_id" to turn.string("stream_id"))) { event ->
                if (event.type == "token") tokens += SystemClock.elapsedRealtime()
                if (event.type == "done" || event.terminal) { ended = event.type; throw CancellationException("ended") }
            } }
        }
        withTimeout(60_000) { while (tokens.isEmpty() && ended.isBlank()) delay(100) }
        link("rate 500")
        val seen = Watch()
        val watcher = watch(vm, seen)
        val began = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
        val sendMs = SystemClock.elapsedRealtime() - began
        link("rate 0")
        watcher.cancel()
        withTimeoutOrNull(120_000) { streaming.join() }
        streaming.cancel()
        runCatching { streamer.closeNative() }
        val during = tokens.toList().filter { it in began..(began + sendMs) }
        val gaps = (listOf(began) + during + listOf(began + sendMs).takeIf { during.isNotEmpty() }.orEmpty()).zipWithNext { a, b -> b - a }
        val stats = proxyStats()
        val content = stored(vm, id, "half kilobyte.")
        status("link_check_c", "size=$size sha256=${sha256(bytes)} name='$name' send_ms=$sendMs notice='${vm.ui.value.notice.take(100)}' ${seen.line()} ${tunnels(stats, size / 2, since)}")
        status("link_check_c_other", "tokens=${tokens.size} during_send=${during.size} longest_gap_ms=${gaps.maxOrNull()} ended='$ended'")
        status("link_check_c_stored", "id=$id content='${content.take(300).replace('\n', '|')}'")
        assertEquals("the chat kept one socket: ${seen.line()}", 1, seen.sockets.size)
        assertEquals("no offline banner: ${seen.line()}", 0, seen.banner)
        assertEquals("the other chat's reply came to its end", "done", ended)
        assertTrue("Hermes holds the message with the file: $content", content.contains("attachments/$name"))
    }

    /**
     * c, bounded) A link that cannot carry a file at all (the proxy ends any connection that sends over 32 KB in 10 s): a queued message with a file tries three
     * times, each on a new socket of its own, then stays in the box as "Not sent" with its Send, and nothing sends it again by itself; the chat keeps its one
     * socket. Once the link carries it, Send sends it.
     */
    @Test(timeout = 900_000) fun aFileThatCannotGetThroughEndsAsNotSentAndWaitsForTheSendTap() = live { vm, tag, made, chats ->
        val id = newChat(vm, "Link check cut $tag").also(chats::add)
        // A reply runs, so the message with the file waits in the box and goes when the reply ends, as a queued message does.
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("Write about 300 words on how clouds form, in plain sentences. $tag")) }
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
        withTimeout(60_000) { while (vm.chat.value.streamId.isBlank()) delay(100) }
        val size = 256 * 1024
        val name = "Synthetic cut $tag.pdf"
        val bytes = synthetic(size, 930L, tag)
        val text = "Synthetic link check $tag, cut. Reply with one short sentence."
        attach(vm, text, document(name, "application/pdf", bytes).also(made::add), name)
        link("rate 20000"); link("cut 32768")
        val seen = Watch()
        val watcher = watch(vm, seen)
        // CHAT-121 follow-up 11: whether the chat still counts as created here when its message with the file goes (follow-up 10's window 4 sent it on
        // the chat's own client). Read again when the message actually goes, after the reply ends.
        fun createdHere() = runCatching { (BeaconViewModel::class.java.getDeclaredField("clientCreatedSessions").apply { isAccessible = true }.get(vm) as Set<*>)
            .contains(requireNotNull(vm.client).scope.session(id).key) }.getOrNull()
        val createdAtTap = createdHere()
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
        val queued = vm.chat.value.queued.single { it.text == text }
        val createdWhenItGoes = withTimeoutOrNull(120_000) { while (vm.chat.value.streamId.isNotBlank()) delay(100); createdHere() }
        status("link_check_cut_client", "created_here_at_tap=$createdAtTap created_here_when_it_goes=$createdWhenItGoes")
        val began = SystemClock.elapsedRealtime()
        withTimeout(300_000) { while (vm.chat.value.queued.firstOrNull { it.id == queued.id }?.fileNotSent() != true) delay(250) }
        val stoppedMs = SystemClock.elapsedRealtime() - began
        val atStop = proxyStats()
        // Nothing sends it again by itself: 45 s later no other connection was cut, and it still waits.
        delay(45_000)
        val later = proxyStats()
        val item = vm.chat.value.queued.first { it.id == queued.id }
        rule.waitForIdle()
        val label = runCatching { rule.onNodeWithTag("queue-not-sent:${item.id}").assertExists(); true }.getOrDefault(false)
        val sendButton = runCatching { rule.onNodeWithTag("queue-send:${item.id}").assertExists(); true }.getOrDefault(false)
        status("link_check_cut", "stopped_ms=$stoppedMs label=$label send_button=$sendButton not_sent=${item.fileNotSent()} notice='${vm.ui.value.notice.take(100)}' " +
            "${seen.line()} at_stop: ${tunnels(atStop, 32768, since)} after_45s: ${tunnels(later, 32768, since)}")
        assertTrue("it says Not sent, with its Send", item.fileNotSent() && label && sendButton)
        assertEquals("three tries, then nothing more", 3, hermesTunnels(later, since).count { it.optBoolean("cut") })
        // CHAT-121 follow-up 11: what the chat held through the cut is checked after the Send tap, so a failed check never leaves the tap untried.
        val socketsAtStop = seen.sockets.size
        val bannerAtStop = seen.banner
        val reconnectingAtStop = seen.reconnecting  // chat-121-11's check, kept (Chat join)
        val lineAtStop = seen.line()
        // The link carries files again: Send sends it.
        link("cut 0"); link("rate 0")
        runCatching { rule.onNodeWithTag("queue-send:${item.id}").performClick() }.onFailure { withContext(Dispatchers.Main.immediate) { vm.retryQueued(item.id) } }
        val content = stored(vm, id, "cut.", 180_000)
        watcher.cancel()
        status("link_check_cut_after_send", "content='${content.take(300).replace('\n', '|')}' ${seen.line()}")
        assertTrue("sent on the tap: $content", content.contains("attachments/$name"))
        assertEquals("the chat kept one socket through the cut: $lineAtStop", 1, socketsAtStop)
        assertEquals("no offline banner through the cut: $lineAtStop", 0, bannerAtStop)
        assertEquals("the chat never said Reconnecting through the cut: $lineAtStop", 0, reconnectingAtStop)
    }

    /** A real PNG of [side]×[side] noise (it barely compresses), so Hermes takes it as the image it says it is. */
    private fun png(side: Int, seed: Long): ByteArray {
        val random = java.util.Random(seed)
        val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until side) for (x in 0 until side) bitmap.setPixel(x, y, 0xFF000000.toInt() or random.nextInt(0x1000000))
        return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * Follow-up 10, d) An image goes on a socket of its own too (image.attach_bytes, in pieces): a real PNG of about 110 KB on a 2 KB/s link. The phone's copy
     * is checked against the file (the app sends the bytes as they are); Hermes names its staged copy itself, so the host finds it by the sha256 printed here.
     */
    @Test(timeout = 900_000) fun anImageArrivesWholeOnItsOwnSocketOnASlowLink() = live { vm, tag, made, chats ->
        val id = newChat(vm, "Link check image $tag").also(chats::add)
        val bytes = png(192, 940L)
        val name = "Synthetic picture $tag.png"
        val text = "Synthetic link check $tag, image. Reply with one short sentence."
        attach(vm, text, document(name, "image/png", bytes).also(made::add), name)
        val held = vm.attachments.value.first { it.string("name") == name }
        val heldSha = held.string("staged_base64").takeIf(String::isNotBlank)?.let { sha256(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) } ?: "kept-file"
        link("rate 2000")
        val seen = Watch()
        val watcher = watch(vm, seen)
        val began = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
        val sendMs = SystemClock.elapsedRealtime() - began
        link("rate 0")
        watcher.cancel()
        val stats = proxyStats()
        val content = stored(vm, id, "image.")
        status("link_check_image", "rate=2000 size=${bytes.size} sha256=${sha256(bytes)} phone_copy_sha256=$heldSha mime=${held.string("mime")} image=1 name='$name' " +
            "send_ms=$sendMs notice='${vm.ui.value.notice.take(100)}' ${seen.line()} ${tunnels(stats, bytes.size / 2, since)}")
        status("link_check_image_link", "${warmups(stats, since)} ${hermesWaits(stats, since)} prewarm=${seen.prewarm.joinToString(",")}")
        status("link_check_image_stored", "id=$id content='${content.take(300).replace('\n', '|')}'")
        assertEquals("the phone sends the image's own bytes", sha256(bytes), heldSha)
        assertTrue("Hermes holds the message: $content", content.contains("image."))
        assertEquals("the image went on one socket of its own: ${tunnels(stats, bytes.size / 2, since)}", 1, hermesTunnels(stats, since).count { it.optLong("up") > bytes.size / 2 })
        // The chat's socket is recorded, not asserted: in windows 1 and 3 it ended together with the image's own socket and the submit went on a new one
        // (follow-up 9's PDFs kept it through the submit), a difference the report names.
        assertEquals("no offline banner: ${seen.line()}", 0, seen.banner)
        assertEquals("the chat never said Reconnecting: ${seen.line()}", 0, seen.reconnecting)
        val answer = reply(vm)
        status("link_check_image_reply", "reply='$answer'")
        assertTrue("the reply comes", answer.isNotBlank())
    }

    /** The call's warm-up runs with Gemini Live: chosen for the case, the earlier choice put back after it. */
    private suspend fun <T> withGemini(body: suspend () -> T): T {
        val voice = VoiceKeyStore(app)
        val previous = voice.settings()
        voice.setSelection(voice.settings("gemini"))
        try {
            check(voice.session()?.provider == "gemini") { "This phone holds no Gemini key for the call's warm-up" }
            return body()
        } finally { withContext(NonCancellable) { runCatching { voice.setSelection(previous) } } }
    }

    private suspend fun say(vm: BeaconViewModel, text: String) {
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)) }
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
    }

    /**
     * Follow-up 10, the call's warm-up on a slow link. With Gemini Live chosen and a chat on screen the app keeps a call ready (VoicePrewarm): a live
     * session on a socket of its own, to Google, set up with the chat's context. A phone's sockets share one upload queue, so what that setup puts on the
     * way delays every other socket's bytes, the chat's pongs included (the dashboard closes a socket whose pong is 20 s late). With no limit first: what
     * each warm-up sends and how long it takes, and what opens one (the chat shown, a reply, an idle minute). Then a short back-and-forth on a 2 KB/s and on
     * a 0.5 KB/s link, as someone chatting would (each message 3 s after the reply before it): the replies come; once the link is slow at most one warm-up
     * goes in all, and a phase with none never says Reconnecting and no pong-sized record of Hermes' sockets waits 20 s. Every wait is recorded.
     */
    @Test(timeout = 1_800_000) fun aCallWarmUpOnASlowLinkNeverTakesTheChatDown() = live { vm, tag, _, chats -> withGemini {
        val failures = mutableListOf<String>()
        val id = newChat(vm, "Link check warm $tag").also(chats::add)
        val target = requireNotNull(vm.client).scope.session(id)
        // n) No limit.
        val normal = Watch()
        val normalWatch = watch(vm, normal)
        var mark = proxyNow()
        val shown = SystemClock.elapsedRealtime()
        // Readiness is recorded, not required: the demo's Gemini may refuse every setup (it has since 16 Nov 22:04).
        val readyMs = withTimeoutOrNull(20_000) { while (!app.calls.prewarm.ready(target)) delay(100); SystemClock.elapsedRealtime() - shown }
        status("link_check_warm_shown", "ready_ms=$readyMs prewarm='${app.calls.prewarm.status}' ${warmups(proxyStats(), mark)}")
        mark = proxyNow()
        say(vm, "Synthetic warm-up check $tag, step 1. Reply with one short sentence.")
        val first = replyTo(vm, "step 1.")
        delay(30_000)
        status("link_check_warm_reply", "reply='$first' ${warmups(proxyStats(), mark)}")
        mark = proxyNow()
        delay(60_000)
        status("link_check_warm_idle", "seconds=60 ${warmups(proxyStats(), mark)}")
        normalWatch.cancel()
        status("link_check_warm_normal_sessions", "${normal.sessionsLine()} prewarm=${normal.prewarm.joinToString(",")}")
        status("link_check_warm_normal_setups", "setups=${normal.setups.joinToString(",")}")
        // Slow links: a short back-and-forth.
        var slowWarmups = 0
        for ((step, rate) in listOf(2 to 2000, 4 to 500)) {
            // Each phase's waits are its own: the proxy's per-tunnel longest waits start again here (a tunnel can span both phases).
            link("reset")
            mark = proxyNow()
            link("rate $rate")
            val seen = Watch()
            val watcher = watch(vm, seen)
            val turns = mutableListOf<String>()
            val answers = mutableListOf<String>()
            for (k in step..step + 1) {
                val began = SystemClock.elapsedRealtime()
                say(vm, "Synthetic warm-up check $tag, step $k. Reply with two short sentences about how glaciers move.")
                val sendMs = SystemClock.elapsedRealtime() - began
                val answer = replyTo(vm, "step $k.", 120_000).also(answers::add)
                turns += "{step=$k send_ms=$sendMs reply_ms=${SystemClock.elapsedRealtime() - began} reply='${answer.take(40)}' notice='${vm.ui.value.notice.take(60)}'}"
                delay(3_000)
            }
            // What a reply's end opens, on the same link.
            delay(40_000)
            val stats = proxyStats()
            link("rate 0")
            watcher.cancel()
            status("link_check_warm_$rate", "rate=$rate turns=${turns.joinToString(",")} ${seen.line()} ${hermesWaits(stats, mark)} ${warmups(stats, mark)}")
            status("link_check_warm_${rate}_sessions", "${seen.sessionsLine()} prewarm=${seen.prewarm.joinToString(",")} setups=${seen.setups.joinToString(",")}")
            // What the fix promises (follow-up 10): once the link is slow, at most one warm-up in all (chat-121-10: one at every look, 25 s apart, while the
            // provider refused them); a slow phase with none never says Reconnecting, shows no banner, and no pong-sized record of Hermes' waits 20 s.
            val opened = during(stats, "generativelanguage.googleapis.com:", mark).count { it.optDouble("opened") >= mark }
            slowWarmups += opened
            val pongWait = during(stats, "", mark).filter { it.string("target").endsWith(":8446") }.maxOfOrNull { it.optDouble("small_max_wait") } ?: 0.0
            if (opened == 0) {
                if (seen.reconnecting > 0) failures += "rate $rate, no warm-up: the chat said Reconnecting (${seen.reconnecting} samples)"
                if (seen.banner > 0) failures += "rate $rate, no warm-up: the offline banner showed"
                if (pongWait >= 20.0) failures += "rate $rate, no warm-up: a pong-sized record of Hermes' sockets waited ${pongWait} s"
                if (answers.any(String::isBlank)) failures += "rate $rate, no warm-up: a reply did not come in 120 s"
            }
            // The next phase starts with no turn running.
            withTimeoutOrNull(120_000) { while (vm.chat.value.streamId.isNotBlank()) delay(500) }
            delay(5_000)
        }
        if (slowWarmups > 1) failures += "warm-ups on the slow links: $slowWarmups (at most one once the link is slow)"
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } }

    /** A call's start as the phone saw it: ms from the tap to each stage, and whether the call took the warm session. */
    private fun callRow(leg: String): String {
        fun stage(name: String) = app.calls.stageLog.firstOrNull { it.optString("stage") == name }
        val stages = listOf("grant_ok", "provider_open", "provider_setup_ok", "first_model_audio").joinToString(" ") { "$it=${stage(it)?.optLong("elapsed_ms") ?: "none"}" }
        // What the provider said when it ended a connection before setup (its close code and reason, as the call records them).
        val errors = app.calls.stageLog.filter { it.optString("stage") == "provider_error" }.take(3).joinToString(",", "[", "]") {
            "{code=${it.opt("close_code") ?: "-"} reason='${it.optString("close_reason").take(160)}' error=${it.optString("error_code")} cause=${it.optString("cause_class")}}"
        }
        return "leg=$leg $stages adopted_warm=${stage("provider_open")?.has("warm") == true} warm_ready=${stage("provider_setup_ok")?.takeIf { it.has("warm") }?.optBoolean("warm") ?: false} " +
            "provider_errors=$errors"
    }

    /**
     * Follow-up 10: on a normal link a call still answers at once, on its warm session. Gemini Live, the chat's Call button: once with the warm session
     * ready, once 5 s after a reply (the chat's context just changed). No speech is sent: each call ends at the model's first audio, and is deleted after.
     */
    @Test(timeout = 900_000) fun aCallStartsOnItsWarmSessionOnANormalLink() = live { vm, tag, _, chats -> withGemini {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS).forEach { runCatching { automation.grantRuntimePermission(app.packageName, it) } }
        val client = requireNotNull(vm.client).detached()
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        try {
            val id = newChat(vm, "Link check call $tag").also(chats::add)
            val target = client.scope.session(id)
            for (leg in listOf("ready", "after_reply")) {
                if (leg == "after_reply") {
                    say(vm, "Synthetic call check $tag. Reply with one short sentence.")
                    reply(vm)
                    delay(5_000)
                } else withTimeoutOrNull(90_000) { while (!app.calls.prewarm.ready(target)) delay(100) }
                val readyAtTap = app.calls.prewarm.ready(target)
                val statusAtTap = app.calls.prewarm.status
                app.calls.stageLog.clear()
                rule.onNodeWithTag("chat-call-beacon").performClick()
                val connected = withTimeoutOrNull(45_000) { while (!(app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() &&
                    app.calls.state.value.state in setOf("thinking", "listening", "speaking"))) delay(100); true } == true
                if (connected) calls += app.calls.state.value.callId
                withTimeoutOrNull(20_000) { while (app.calls.stageLog.none { it.optString("stage") == "first_model_audio" }) delay(50) }
                val row = callRow(leg)
                status("link_check_call_$leg", "$row ready_at_tap=$readyAtTap prewarm_at_tap='$statusAtTap' connected=$connected")
                if (!connected || "first_model_audio=none" in row) failures += "leg $leg did not answer: $row"
                app.calls.hangup()
                withTimeoutOrNull(60_000) { while (app.calls.service != null || app.calls.state.value.active) delay(100) }
                delay(2_000)
            }
        } finally { withContext(NonCancellable) {
            runCatching { app.calls.hangup() }
            for (call in calls) runCatching { withTimeoutOrNull(20_000) { client.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE") } }
            runCatching { client.closeNative() }
        } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } }
}
