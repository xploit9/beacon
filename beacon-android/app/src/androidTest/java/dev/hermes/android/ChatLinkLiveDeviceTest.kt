package dev.hermes.android

import android.os.Bundle
import android.os.SystemClock
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
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * CHAT-121 follow-up 11, live on the demo Hermes through the host's link proxy (fu11_link_proxy.py, the emulator's http_proxy for the run; control on
 * 10.0.2.2:8896): no reply ever hangs, no failure takes the chat's connection down. Synthetic text only; each case runs alone with the demo sign-in
 * staged (`--arg beaconLiveService=1`), deletes its chats through the app's own Delete, and pauses the call warm-up so only the case's own traffic is
 * on the link (CallPrewarm.paused; the warm-up's own rule is follow-up 10's). The host records the screen.
 */
class ChatLinkLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })

    private suspend fun link(line: String): JSONObject = withContext(Dispatchers.IO) {
        java.net.Socket("10.0.2.2", 8896).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply { write((line + "\n").toByteArray()); flush() }
            JSONObject(socket.getInputStream().bufferedReader().readLine())
        }
    }

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

    private suspend fun say(vm: BeaconViewModel, text: String) {
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)) }
        withContext(Dispatchers.Main.immediate) { vm.send() }.join()
    }

    /** Hermes' own rows of chat [id] with [role] whose words contain [marker] (read on a connection of its own). */
    private suspend fun rows(vm: BeaconViewModel, id: String, role: String, marker: String): List<String> = runCatching {
        vm.client?.detached()?.let { c -> try { c.nativeGet("/api/sessions/$id/messages", mapOf("profile" to c.auth.profile)) } finally { c.closeNative() } }
    }.getOrNull()?.rows("messages")?.filter { it.string("role") == role && it.string("content", it.string("text")).contains(marker) }
        ?.map { it.string("content", it.string("text")) }.orEmpty()

    /** The reply to the last user message on screen: its first words once it ended, or "" when none came in [timeoutMs]. */
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

    /**
     * What the chat showed every 250 ms: whether [text] waited in the box or came back to the composer, the notices, the statuses, and the longest stretch a
     * running reply showed nothing at all (no new words, reasoning or tools, and no status line).
     */
    private class Timeline { var waited = false; var notSent = false; var composerWords = false; val notices = linkedSetOf<String>(); val statuses = linkedSetOf<String>()
        var longestSilentMs = 0L; var linkTries = 0; var unreachable = false; var boxAtMs = -1L; val changes = mutableListOf<String>() }
    /** Statuses that say nothing about a lost reply: the chat shows them while a reply simply runs. */
    private val quietStatuses = setOf("", "Working", "Sending", "Responding")
    @Suppress("UNCHECKED_CAST")
    private fun BeaconViewModel.linkTries(submission: String): Int = runCatching {
        (BeaconViewModel::class.java.getDeclaredField("linkFailures").apply { isAccessible = true }.get(this) as Map<String, Int>)[submission] ?: 0
    }.getOrDefault(0)
    private fun CoroutineScope.sample(vm: BeaconViewModel, seen: Timeline, text: String = "") = launch(Dispatchers.Default) {
        val began = SystemClock.elapsedRealtime()
        var silentSince = -1L
        var progress = ""
        var last = ""
        while (isActive) {
            val chat = vm.chat.value
            val item = if (text.isBlank()) null else chat.queued.firstOrNull { it.text == text }
            if (item?.waitingForConnection() == true) { seen.waited = true; if (seen.boxAtMs < 0) seen.boxAtMs = SystemClock.elapsedRealtime() - began }
            if (item != null) seen.linkTries = maxOf(seen.linkTries, vm.linkTries(item.submissionId))
            if (vm.ui.value.origin in dev.hermes.android.data.HermesReach.origins.value) seen.unreachable = true
            if (item?.fileNotSent() == true) seen.notSent = true
            if (text.isNotBlank() && vm.composerText() == text) seen.composerWords = true
            vm.ui.value.notice.takeIf(String::isNotBlank)?.let(seen.notices::add)
            chat.status.takeIf(String::isNotBlank)?.let(seen.statuses::add)
            val now = SystemClock.elapsedRealtime()
            val shown = "${chat.liveText.length}|${chat.liveReasoning.length}|${chat.liveTools.size}"
            if (chat.streamId.isNotBlank() && chat.status in quietStatuses && shown == progress) {
                if (silentSince < 0) silentSince = now
                seen.longestSilentMs = maxOf(seen.longestSilentMs, now - silentSince)
            } else silentSince = -1
            progress = shown
            val state = "stream=${chat.streamId.isNotBlank()} status='${chat.status}' box=${item?.let { "${it.state}/${it.outcome}" } ?: "-"} live=${chat.liveText.length}"
            if (state != last && seen.changes.size < 60) seen.changes += "${now - began}ms:$state"
            last = state
            delay(250)
        }
    }

    private fun BeaconViewModel.composerText(): String = runCatching {
        @Suppress("UNCHECKED_CAST")
        (BeaconViewModel::class.java.getDeclaredField("_composer").apply { isAccessible = true }.get(this) as kotlinx.coroutines.flow.MutableStateFlow<TextFieldValue>).value.text
    }.getOrDefault("")

    private suspend fun ready(vm: BeaconViewModel) = withTimeout(120_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }

    private fun live(body: suspend CoroutineScope.(vm: BeaconViewModel, tag: String, chats: MutableList<String>) -> Unit) = runBlocking<Unit> {
        assumeTrue("Opt-in: device-test.py --isolated --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        require(app.packageName.endsWith(".debug"))
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val tag = UUID.randomUUID().toString().take(6)
        val chats = mutableListOf<String>()
        app.calls.prewarm.paused = true
        app.calls.prewarm.forget()
        try {
            link("cut 0"); link("rate 0"); link("marker off")
            ready(vm)
            withContext(Dispatchers.Main.immediate) { if (vm.ui.value.tab != "Chat") vm.tab("Chat") }
            val frames = launch(Dispatchers.Default) { while (isActive) { runCatching { rule.mainClock.advanceTimeBy(250) }; delay(250) } }
            try { this.body(vm, tag, chats) } finally { frames.cancel() }
        } finally { withContext(NonCancellable) {
            runCatching { link("cut 0"); link("rate 0"); link("marker off") }
            app.calls.prewarm.paused = false
            runCatching { withTimeoutOrNull(15_000) { withContext(Dispatchers.Main.immediate) { vm.cancel() }.join() } }
            // Every chat this case made, the ones a busy create may have made later included (their titles carry the tag).
            vm.ui.value.sessions.filter { it.string("title").contains(tag) }.map { it.string("session_id", it.string("id")) }.filter(String::isNotBlank).forEach { if (it !in chats) chats += it }
            val deleted = chats.map { chat -> chat to (runCatching { withTimeoutOrNull(40_000) {
                var ok = false
                repeat(3) { if (!ok) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(chat, "/api/session/delete", done = "Deleted") }.join()
                    ok = vm.ui.value.notice == "Deleted"
                    if (!ok) delay(3_000)
                } }
                ok
            } == true }.getOrDefault(false)) }
            status("link_check_cleanup", "tag=$tag deleted=$deleted")
        } }
    }

    /**
     * Backlog 82 as follow-up 10's windows 3 and 4 had it: on a 2 KB/s link, 43 KB already sit in the phone's one upload queue (a call warm-up's setup;
     * here the proxy's `fill`), so the next message's connection is not ready within its 15 s. Root's rule (msg_ec3f92a4d60a): it waits in the box with
     * the outbox's look, goes again by itself once the link carries it, reaches Hermes exactly once, and its reply shows; its words never come back to the
     * composer.
     */
    @Test(timeout = 900_000) fun aSendTheLinkCannotCarryNowWaitsInTheBoxAndArrivesOnceByItself() = queueCase("fillnext 43000")

    /** The same where the burst is ahead of everything: the ticket's own request waits behind it, past the ticket's 20 s life (follow-up 11's first block). */
    @Test(timeout = 900_000) fun aSendWhoseTicketWaitsBehindTheQueueAlsoWaitsInTheBoxAndArrivesOnce() = queueCase("fill 43000")

    private fun queueCase(burst: String) = live { vm, tag, chats ->
        val id = newChat(vm, "Link check queue $tag").also(chats::add)
        say(vm, "Synthetic link check $tag, first. Reply with one short sentence.")
        assertTrue("the first reply", reply(vm).isNotBlank())
        link("rate 2000")
        link(burst)
        val text = "Synthetic link check $tag, behind the queue. Reply with one short sentence."
        val seen = Timeline()
        val sampler = sample(vm, seen, text)
        val began = SystemClock.elapsedRealtime()
        say(vm, text)
        val held = withTimeoutOrNull(240_000) { while (rows(vm, id, "user", "behind the queue.").isEmpty()) delay(2_000); true } == true
        val heldAfterMs = SystemClock.elapsedRealtime() - began
        sampler.cancel()
        // Its own reply, from Hermes' saved rows: an assistant row after the marked user row.
        val answer = withTimeoutOrNull(240_000) { while (true) { replyAfter(vm, id, "behind the queue.")?.let { return@withTimeoutOrNull it }; delay(2_000) }; @Suppress("UNREACHABLE_CODE") "" }.orEmpty()
        link("rate 0")
        val copies = rows(vm, id, "user", "behind the queue.").size
        status("link_check_queue", "burst='$burst' held=$held held_after_ms=$heldAfterMs waited_in_box=${seen.waited} box_at_ms=${seen.boxAtMs} link_tries=${seen.linkTries} unreachable=${seen.unreachable} " +
            "not_sent=${seen.notSent} composer_got_words=${seen.composerWords} " +
            "copies=$copies reply='${answer.take(60)}' notices=${seen.notices} statuses=${seen.statuses} longest_silent_ms=${seen.longestSilentMs} changes=${seen.changes.joinToString(",")}")
        assertTrue("it waited in the box (the first try did not get through): ${seen.changes}", seen.waited)
        assertTrue("by the new rule (a try the link could not carry), not only the outbox's: link_tries=${seen.linkTries} unreachable=${seen.unreachable}", seen.linkTries >= 1)
        assertFalse("its words never came back to the composer", seen.composerWords)
        assertEquals("Hermes holds it exactly once", 1, copies)
        assertTrue("its own reply is saved after it", answer.isNotBlank())
    }

    /** The first words of Hermes' saved assistant row after the user row of chat [id] that contains [marker], or null while there is none. */
    private suspend fun replyAfter(vm: BeaconViewModel, id: String, marker: String): String? = runCatching {
        vm.client?.detached()?.let { c -> try { c.nativeGet("/api/sessions/$id/messages", mapOf("profile" to c.auth.profile)) } finally { c.closeNative() } }
    }.getOrNull()?.rows("messages")?.let { rows ->
        val at = rows.indexOfFirst { it.string("role") == "user" && it.string("content", it.string("text")).contains(marker) }
        if (at < 0) null else rows.drop(at + 1).firstOrNull { it.string("role") == "assistant" && it.string("content", it.string("text")).isNotBlank() }
            ?.let { it.string("content", it.string("text")).take(60).replace('\n', ' ') }
    }

    /** Backlog 82: the link drops every connection to Hermes while a reply streams. The reply comes, or the chat says why; never a silent running turn. */
    @Test(timeout = 900_000) fun aReplyWhoseLinkDropsMidwayComesBackOrSaysWhy() = live { vm, tag, chats ->
        newChat(vm, "Link check drop $tag").also(chats::add)
        say(vm, "Write about 350 words on how glaciers shape valleys, in plain sentences. $tag")
        withTimeout(120_000) { while (vm.chat.value.liveText.length < 40) delay(100) }
        val seen = Timeline()
        val sampler = sample(vm, seen)
        val dropped = link("drop")
        val answer = reply(vm, 300_000)
        sampler.cancel()
        status("link_check_drop", "dropped=${dropped.opt("dropped")} reply='${answer.take(60)}' statuses=${seen.statuses} notices=${seen.notices} " +
            "longest_silent_ms=${seen.longestSilentMs} changes=${seen.changes.joinToString(",")}")
        assertTrue("the reply comes: ${seen.changes}", answer.isNotBlank())
    }

    /** Backlog 82: every byte stops both ways for 75 s while a reply streams, and nothing closes. The reply comes, or the chat says why meanwhile. */
    @Test(timeout = 900_000) fun aReplyWhoseLinkStallsMidwayComesBackOrSaysWhy() = live { vm, tag, chats ->
        newChat(vm, "Link check stall $tag").also(chats::add)
        say(vm, "Write about 350 words on how coral reefs grow, in plain sentences. $tag")
        withTimeout(120_000) { while (vm.chat.value.liveText.length < 40) delay(100) }
        val seen = Timeline()
        val sampler = sample(vm, seen)
        link("stall 75")
        val answer = reply(vm, 360_000)
        sampler.cancel()
        status("link_check_stall", "reply='${answer.take(60)}' statuses=${seen.statuses} notices=${seen.notices} longest_silent_ms=${seen.longestSilentMs} " +
            "changes=${seen.changes.joinToString(",")}")
        assertTrue("the reply comes: ${seen.changes}", answer.isNotBlank())
    }

    /**
     * Backlog 67, the live confirmation (Root msg_52dc6d4bfe4c; the permanent proof is the plugin suite's route-level test). The demo runs a throwaway
     * copy of this tree's plugin whose test-only shim holds every admission worker while the marker exists, so a new chat's relayed create finds the pool
     * busy. It is answered on the socket (the app says Hermes is busy), the chat whose reply runs goes on to its end, and a new chat opens once the pool is
     * free again.
     */
    @Test(timeout = 900_000) fun aNewChatThePluginFindsBusyIsAnsweredAndTheOtherChatGoesOn() = live { vm, tag, chats ->
        val a = newChat(vm, "Link check busy A $tag").also(chats::add)
        say(vm, "Write about 300 words on how rivers carve canyons, in plain sentences. $tag")
        withTimeout(120_000) { while (vm.chat.value.liveText.length < 40) delay(100) }
        val before = link("now").optDouble("t")
        val holdsBefore = link("holds").optInt("holds")
        val notices = linkedSetOf<String>()
        val watcher = launch(Dispatchers.Default) { while (isActive) { vm.ui.value.notice.takeIf(String::isNotBlank)?.let(notices::add); delay(100) } }
        link("marker on")
        try {
            withContext(Dispatchers.Main.immediate) { vm.newChat(route(json("title" to "Link check busy B $tag", "request_id" to "link-check-" + UUID.randomUUID()))) }.join()
            delay(5_000)
        } finally { link("marker off") }
        watcher.cancel()
        val stats = link("stats $before")
        val tunnels = stats.rows("tunnels").filter { it.string("target").endsWith(":8446") }
        // Chat A's reply goes on to its end, whatever the busy create did.
        val replyA = withTimeoutOrNull(300_000) { while (rows(vm, a, "assistant", "").isEmpty()) delay(2_000); true } == true
        val c = newChat(vm, "Link check busy C $tag").also(chats::add)
        val holds = link("holds").optInt("holds") - holdsBefore
        status("link_check_busy", "holds=$holds notices=$notices reply_a=$replyA chat_c='${c.take(12)}' tunnels=${tunnels.size} server_ended=${tunnels.count { !it.isNull("server_closed") }}")
        assertTrue("the plugin's pool was held for the new chat's create (the test-only shim fired)", holds >= 1)
        assertTrue("chat A's reply came", replyA)
        assertTrue("a new chat opens once the pool is free", c.isNotBlank() && c != a)
    }

    /**
     * Backlog 81: the demo's Gemini key is out of credits (close 1011 "Your prepayment credits are depleted", every call since 16 Nov). A call from the chat's
     * Call button ends at once with one plain line, and its setup is not sent again (follow-up 10 counted about six in 12 s). Gemini only; no speech is
     * sent, nothing is kept, and the call is to the demo Hermes, never to a phone.
     */
    @Test(timeout = 600_000) fun aCallTheProviderRefusesForMoneySaysSoOnceAndStops() = live { vm, tag, chats ->
        val voice = VoiceKeyStore(app)
        val previous = voice.settings()
        voice.setSelection(voice.settings("gemini"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS).forEach { runCatching { automation.grantRuntimePermission(app.packageName, it) } }
        var callId = ""
        try {
            check(voice.session()?.provider == "gemini") { "This phone holds no Gemini key" }
            newChat(vm, "Link check refused $tag").also(chats::add)
            val before = link("now").optDouble("t")
            app.calls.stageLog.clear()
            val tapped = SystemClock.elapsedRealtime()
            rule.onNodeWithTag("chat-call-beacon").performClick()
            val ended = withTimeoutOrNull(45_000) { while (!(app.calls.state.value.error.isNotBlank() && !app.calls.state.value.active)) delay(100); true } == true
            val endedAfterMs = SystemClock.elapsedRealtime() - tapped
            callId = app.calls.state.value.callId
            val line = app.calls.state.value.error
            delay(10_000)
            val gemini = link("stats $before").rows("tunnels").filter { it.optDouble("opened") >= before && it.string("target").startsWith("generativelanguage") }
            val refusals = app.calls.stageLog.filter { it.optString("stage") == "provider_error" }
            status("link_check_refused", "ended=$ended ended_after_ms=$endedAfterMs line='$line' gemini_sockets=${gemini.size} provider_errors=${refusals.size} " +
                "first='${refusals.firstOrNull()?.optString("close_reason")?.take(80)}'")
            assertTrue("the call ended", ended)
            assertEquals("one plain line", "Your Gemini credits ran out.", line)
            assertTrue("its setup was not sent again: ${gemini.size} sockets", gemini.size <= 1)
        } finally { withContext(NonCancellable) {
            runCatching { app.calls.hangup() }
            if (callId.isNotBlank()) runCatching { vm.client?.detached()?.let { c -> try { withTimeoutOrNull(20_000) { c.nativeRequest("/api/plugins/beacon/mobile/calls/$callId", "DELETE") } } finally { c.closeNative() } } }
            runCatching { voice.setSelection(previous) }
        } }
    }
}
