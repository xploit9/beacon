package dev.hermes.android.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Live proof that the voice agent and Hermes are one: a fact the owner says on a call lands in Hermes' own memory with no phone
 * approval, a new call and a new Hermes chat both recall it, a fact told to a Hermes chat is recalled on a new call, and the
 * assistant speaks as Hermes itself. Gemini only, on the isolated demo Hermes (port 8446), with synthetic facts and synthetic
 * speech made in the test with the phone's own Gemini key. Hermes memory is read and cleaned through the authenticated client the
 * Memory screen uses; no file is read and no audio is kept.
 *
 * Every call and every Hermes chat starts in its own new chat, so a recall can only come from Hermes memory, never from that
 * chat's own history or earlier voice dialogue.
 */
class VoiceMemoryLiveTest {
    @get:Rule val rule = createAndroidComposeRule<BeaconActivity>()

    private companion object {
        const val PROVIDER = "gemini"
        const val RATE = 16_000
        const val SAVE_FRUIT = "Please remember that my favourite fruit is mango."
        const val ASK_FRUIT = "What is my favourite fruit?"
        const val SAVE_DOG = "Please remember that my dog is called Pixel."
        const val ASK_DOG = "What is my dog called?"
        const val NOTES_PATH = "/api/plugins/beacon/memory/documents"
        const val CALLS_PATH = "/api/plugins/beacon/mobile/calls"
        val Mango = Regex("(?i)\\bmango\\b")
        val Pixel = Regex("(?i)\\bpixel\\b")
        val FruitWords = Regex("(?i)\\b(?:fruit|favou?rite)\\b")
        val DogWord = Regex("(?i)\\bdog\\b")
        val EntryBreak = Regex("(?:\\r?\\n)§(?:\\r?\\n)")
        /** The assistant handing the job to Hermes as if Hermes were someone else. */
        val SpeaksAsAnother = Regex(
            "(?i)\\b(?:ask(?:ed|ing)?|tell(?:ing)?|told|pass(?:ed|ing)?|hand(?:ed|ing)?|relay(?:ed|ing)?|forward(?:ed|ing)?|notif(?:y|ied|ying))\\b[^.!?\\n]{0,24}\\bHermes\\b|\\blet\\s+Hermes\\b")
        val Finished = setOf("completed", "failed", "denied", "expired", "cancelled", "uncertain")
    }

    /** What one run holds, so the cleanup knows exactly what to remove. */
    private class MemoryRun(val app: BeaconApplication, val vm: BeaconViewModel) {
        var connected: BeaconClient? = null
        val client: BeaconClient get() = checkNotNull(connected) { "The test is not connected to the demo Hermes" }
        val chats = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val timings = JSONObject()
        val passed = JSONObject()
        @Volatile var phase = "starting"
        /** Every note that was in Hermes memory before the test wrote anything, so cleanup never removes one of them. */
        var baseline = emptySet<String>()
    }

    private class ToolSighting(val receipt: CallToolReceipt, val atMs: Long)

    private class VoiceMemoryTurn(
        val callId: String, val heard: String, val reply: String, val everythingSaid: String,
        val sightings: List<ToolSighting>, val approvalShown: Boolean,
        val connectMs: Long, val firstReplyMs: Long, val memoryReceiptMs: Long,
    ) {
        val tools get() = sightings.joinToString { it.receipt.toolName + ":" + it.receipt.status + it.receipt.approvedBy?.let { by -> "/$by" }.orEmpty() }
    }

    private suspend fun eventually(what: suspend () -> String, ms: Long = 90_000, condition: suspend () -> Boolean) {
        val reached = withTimeoutOrNull(ms) { while (!condition()) delay(100); true }
        if (reached != true) throw AssertionError("Timed out after ${ms / 1000}s: ${what()}")
    }

    private fun read(type: Class<*>, owner: Any, name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /** One phrase as PCM16 at [RATE], spoken by Gemini's speech model with the key saved on this test phone. */
    private suspend fun spoken(app: BeaconApplication, phrase: String): ByteArray {
        val credentials = requireNotNull(VoiceKeyStore(app).session(PROVIDER)) { "A Gemini key on the test phone is required to speak the fixture" }
        val http = okhttp3.OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            val source = withContext(Dispatchers.IO) {
                http.newCall(GeminiSpeech.request(credentials, phrase)).execute().use { response ->
                    check(response.isSuccessful) { "Fixture speech failed: ${response.code}" }
                    val value = requireNotNull(response.body).bytes(); check(value.size <= GeminiSpeech.MAX_JSON_BYTES)
                    GeminiSpeech.pcm(JSONObject(value.toString(Charsets.UTF_8)))
                }
            }
            // Gemini speaks at 24 kHz; the call listens at [RATE].
            val count = (source.size / 2).toLong() * RATE / GeminiWire.OUTPUT_RATE
            val output = ByteArray(count.toInt() * 2)
            for (index in 0 until count.toInt()) {
                val at = (index.toLong() * GeminiWire.OUTPUT_RATE / RATE).toInt() * 2
                output[index * 2] = source[at]; output[index * 2 + 1] = source[at + 1]
            }
            source.fill(0)
            return output
        } finally { withContext(NonCancellable + Dispatchers.IO) { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() } }
    }

    // ---- Hermes memory, read and cleaned the way the Memory screen does (authenticated client, no file access) ----

    private suspend fun readMemory(client: BeaconClient): NativeMemoryState {
        val profile = mapOf("profile" to client.auth.profile)
        return NativeMemoryState.parse(client.scope.key, client.nativeRequest("/api/memory", params = profile), client.nativeRequest(NOTES_PATH, params = profile))
    }

    private fun NativeMemoryState.notes() = documents.joinToString("\n") { it.content }

    private fun entries(content: String) = content.split(EntryBreak).map(String::trim).filter(String::isNotBlank)

    /**
     * Saves each notes document without the entries [remove] matches (the Memory screen's save, with its revision). An entry that
     * Hermes merged into one of the notes that were there before is put back as that note, so nothing else is lost. Returns how
     * many entries went.
     */
    private suspend fun removeNotes(run: MemoryRun, remove: (String) -> Boolean): Int {
        val client = run.client
        repeat(4) {
            var removed = 0
            var conflict = false
            for (document in readMemory(client).documents) {
                val all = document.content.split(EntryBreak)
                val kept = all.mapNotNull { entry -> if (!remove(entry)) entry else run.baseline.firstOrNull { it in entry } }
                if (all.size == kept.size && all.zip(kept).all { (before, after) -> before == after }) continue
                removed += all.size - kept.size
                try {
                    val saved = client.nativeRequest(NOTES_PATH, "PUT", document.saveBody(kept.joinToString("\n§\n")), mapOf("profile" to client.auth.profile))
                    check(saved.optBoolean("ok")) { "Hermes did not confirm the memory save" }
                } catch (error: ApiFailure) { if (error.status == 409) conflict = true else throw error }
            }
            if (!conflict) return removed
        }
        error("Hermes memory kept changing while the test notes were removed")
    }

    // ---- Chats ----

    /** A new chat, opened the way the New chat button does. */
    private suspend fun openChat(run: MemoryRun, title: String): String {
        val vm = run.vm
        rule.runOnIdle { vm.tab("Chat") }
        eventually({ "the Chat tab to open" }) { vm.ui.value.tab == "Chat" && !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) }
        val previous = vm.chat.value.sessionId
        withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to title)) }.join()
        eventually({ "a new chat \"$title\" to open" }) {
            vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady &&
                !vm.ui.value.newChatOpening && vm.authenticatedScope()?.session == vm.chat.value.sessionId
        }
        return vm.chat.value.sessionId.also { run.chats += it }
    }

    private fun replyTo(messages: List<Message>, text: String): String {
        val asked = messages.indexOfLast { it.role == "user" && it.text.trim() == text }
        return if (asked < 0) "" else messages.drop(asked + 1).filter { it.role == "assistant" }.joinToString(" ") { it.text }.trim()
    }

    /** Sends [text] through the chat composer's send path and waits for Hermes' finished reply. */
    private suspend fun chatTurn(run: MemoryRun, text: String): String {
        val vm = run.vm
        withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)); vm.send() }.join()
        eventually({ "Hermes' reply to the chat message \"$text\" (status: ${vm.chat.value.status})" }, 180_000) {
            vm.chat.value.streamId.isBlank() && replyTo(vm.chat.value.messages, text).isNotBlank()
        }
        return replyTo(vm.chat.value.messages, text)
    }

    // ---- One spoken turn on a live call ----

    private suspend fun drainSpeech(pcm: NativePcm) {
        val queue = read(NativePcm::class.java, pcm, "queue") as PcmQueue
        val ledger = read(NativePcm::class.java, pcm, "ledger") as PlaybackLedger
        val lock = read(NativePcm::class.java, pcm, "lock")!!
        val bytes = PcmQueue::class.java.getDeclaredField("bytes").apply { isAccessible = true }
        var quiet = 0L
        eventually({ "the assistant's speech to finish playing" }) {
            val empty = synchronized(lock) { synchronized(queue) { bytes.getInt(queue) == 0 } && ledger.pendingBytes == 0 }
            if (!empty) { quiet = 0; false } else {
                val now = System.nanoTime(); if (quiet == 0L) quiet = now
                now - quiet > 1_500_000_000L
            }
        }
    }

    private suspend fun stream(direct: DirectVoice, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + RATE * 2 / 50, bytes.size)
            check(direct.pcm(bytes.copyOfRange(offset, end)) { true }) { "Speech input stopped before the fixture finished" }
            offset = end; delay(20)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun endSpeech(direct: DirectVoice) {
        val pending = read(DirectVoice::class.java, direct, "microphone") as kotlinx.coroutines.channels.Channel<*>
        eventually({ "the synthetic speech to reach the live session" }, 5_000) { pending.isEmpty }
        check(direct.send(GeminiWire.endAudio())) { "The synthetic input pause was not accepted" }
    }

    /**
     * Opens a new chat, calls from it, says [phrase] with synthetic speech, waits for the assistant's finished answer (and, when
     * [savesMemory], for the Hermes memory tool to finish), then ends and deletes the call. [heard] must show in what the call
     * transcribed, so a misheard fixture fails as such instead of as a memory failure.
     */
    private suspend fun callTurn(run: MemoryRun, label: String, phrase: String, heard: List<Regex>, savesMemory: Boolean): VoiceMemoryTurn {
        val app = run.app
        // The owner's words are made before the call, so the live session never waits for them.
        val fixture = spoken(app, phrase)
        val sightings = ConcurrentLinkedQueue<ToolSighting>()
        val approvalShown = AtomicBoolean(false)
        val firstReplyAt = AtomicLong(0)
        val receiptAt = AtomicLong(0)
        val spokenAt = AtomicLong(0)
        var watcher: Job? = null
        try {
            val session = openChat(run, "Voice memory $label")
            val previousCall = app.calls.state.value.callId
            val previousError = app.calls.state.value.error
            val tapped = SystemClock.elapsedRealtime()
            rule.onNodeWithTag("chat-call-beacon").performClick()
            var attemptStarted = false
            eventually({ "the $label call to start (error: ${app.calls.state.value.failureReason} ${app.calls.state.value.error})" }, 45_000) {
                val state = app.calls.state.value
                if (state.active || state.callId != previousCall || state.error != previousError) attemptStarted = true
                check(!(attemptStarted && !state.active && state.error.isNotBlank())) { "The $label call failed to connect: ${state.failureReason} ${state.error}" }
                state.active && state.state in setOf("thinking", "listening", "speaking")
            }
            val connectMs = SystemClock.elapsedRealtime() - tapped
            val callId = app.calls.state.value.callId
            run.calls += callId
            assertEquals("The $label call used the Gemini live voice", PROVIDER, app.calls.state.value.voiceProvider)
            val context = run.client.nativeCallContext(callId)
            assertEquals("The $label call belongs to the demo profile", run.client.auth.profile, context.getString("profile"))
            assertEquals("The $label call was started from the chat opened for it", session, context.getString("selected_session_id"))
            val service = requireNotNull(app.calls.service)
            service.mute(true) // The test speaks; the phone's own microphone stays out of it.
            assertTrue(app.calls.state.value.muted)
            val pcm = read(BeaconCallService::class.java, service, "pcm") as NativePcm
            val direct = read(BeaconCallService::class.java, service, "directVoice") as DirectVoice
            // The live model's own greeting plays out first, so it is not taken for the answer.
            withTimeoutOrNull(35_000) { while (app.calls.state.value.transcripts.none { it.role == "assistant" && it.final } && app.calls.state.value.error.isBlank()) delay(100) }
            drainSpeech(pcm)
            val priorAssistant = app.calls.state.value.transcripts.filter { it.role == "assistant" }.map { it.eventId }.toSet()
            val priorUsers = app.calls.state.value.transcripts.filter { it.role == "user" }.map { it.eventId }.toSet()
            val priorReceipts = app.calls.state.value.toolReceipts.map { it.requestId + "/" + it.status }.toSet()
            fun newAssistant() = app.calls.state.value.transcripts.filter { it.role == "assistant" && it.eventId !in priorAssistant && it.text.isNotBlank() }
            fun newUser() = app.calls.state.value.transcripts.filter { it.role == "user" && it.final && it.eventId !in priorUsers && it.text.isNotBlank() }
            // Receipts and the first reply are noted as they appear: the receipt list keeps only the latest few.
            watcher = CoroutineScope(Dispatchers.Default).launch {
                val seen = HashSet(priorReceipts)
                while (isActive) {
                    val state = app.calls.state.value
                    val now = SystemClock.elapsedRealtime()
                    for (receipt in state.toolReceipts) if (seen.add(receipt.requestId + "/" + receipt.status)) {
                        sightings.add(ToolSighting(receipt, now))
                        if (receipt.toolName == "memory" && receipt.status == "completed") receiptAt.compareAndSet(0, now)
                    }
                    if (state.toolApprovals.isNotEmpty()) approvalShown.set(true)
                    if (spokenAt.get() > 0 && newAssistant().isNotEmpty()) firstReplyAt.compareAndSet(0, now)
                    delay(25)
                }
            }
            run.phase = "$label: speaking"
            stream(direct, ByteArray(RATE * 2 * 600 / 1000)); stream(direct, fixture); spokenAt.set(SystemClock.elapsedRealtime())
            stream(direct, ByteArray(RATE * 2 * 2200 / 1000)); endSpeech(direct)
            run.phase = "$label: heard"
            eventually({ "the $label call to hear \"$phrase\" (heard: \"${newUser().joinToString(" ") { it.text }}\")" }, 60_000) {
                val text = newUser().joinToString(" ") { it.text }
                heard.all { it.containsMatchIn(text) }
            }
            run.phase = "$label: answered"
            eventually({ "an answer to \"$phrase\" (tools: ${sightings.joinToString { it.receipt.toolName + ":" + it.receipt.status }})" }) { newAssistant().any { it.final } }
            if (savesMemory) eventually({ "a finished Hermes memory tool request (seen: ${sightings.joinToString { it.receipt.toolName + ":" + it.receipt.status }})" }) {
                sightings.any { it.receipt.toolName == "memory" && it.receipt.status in Finished }
            }
            drainSpeech(pcm)
            delay(1_000) // A closing line can still be transcribing.
            watcher.cancelAndJoin()
            val said = app.calls.state.value.transcripts.filter { it.role == "assistant" && it.text.isNotBlank() }.joinToString(" ") { it.text }
            val turn = VoiceMemoryTurn(callId, newUser().joinToString(" ") { it.text }, newAssistant().joinToString(" ") { it.text }, said, sightings.toList(), approvalShown.get(), connectMs,
                firstReplyAt.get().takeIf { it > 0 }?.let { (it - spokenAt.get()).coerceAtLeast(0) } ?: -1,
                receiptAt.get().takeIf { it > 0 }?.let { (it - spokenAt.get()).coerceAtLeast(0) } ?: -1)
            run.phase = "$label: hanging up"
            app.calls.hangup(); eventually({ "the $label call to end" }, 60_000) { !app.calls.state.value.active && app.calls.service == null }
            val record = run.client.nativeRequest("$CALLS_PATH/$callId")
            assertTrue("The $label call closed", record.optBoolean("closed")); assertFalse("No call audio is kept", record.optBoolean("audio_available"))
            run.client.nativeRequest("$CALLS_PATH/$callId", "DELETE"); run.calls -= callId
            return turn
        } finally { withContext(NonCancellable) { watcher?.cancelAndJoin(); fixture.fill(0) } }
    }

    private fun publish(run: MemoryRun, outcome: String) {
        val report = JSONObject().put("provider", PROVIDER).put("outcome", outcome).put("phase", run.phase).put("passed", run.passed)
            .put("timings_ms", run.timings).put("fixture_source", "synthetic private PCM16").put("version_code", BuildConfig.VERSION_CODE)
        runCatching { File(run.app.filesDir, "voice-memory-$PROVIDER.json").writeText(report.toString(), Charsets.UTF_8) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("beacon_voice_memory", report.toString()) })
    }

    @Test(timeout = 1_200_000) fun geminiVoiceAndHermesShareOneMemory() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        assumeTrue(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "The microphone grant is required (device-test.py --grant-mic)" }
        val voiceStore = VoiceKeyStore(app)
        require(voiceStore.hasKey(PROVIDER)) { "The Gemini key saved on the test phone is required to speak the fixture" }
        val authFile = File(app.filesDir, "live-auth.json")
        val auth = if (authFile.isFile) JSONObject(authFile.readText(Charsets.UTF_8).removePrefix("﻿")).also { check(authFile.delete()) } else null
        fun demoOnly(origin: String) = require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "This test saves and removes memory notes, so it only runs against the isolated demo Hermes" }
        auth?.let { demoOnly(it.getString("origin")) }
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val run = MemoryRun(app, vm)
        val previousVoice = voiceStore.settings()
        var failure: Throwable? = null
        var memoryTouched = false
        try {
            voiceStore.setSelection(voiceStore.settings(PROVIDER))
            if (auth != null) {
                loginLiveTest(app, auth.getString("origin"), auth)
                withContext(Dispatchers.Main.immediate) { vm.connect(auth.getString("origin")) }.join()
            }
            eventually({ "the app to connect to the demo Hermes" }) { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
            run.connected = requireNotNull(vm.client).detached()
            demoOnly(run.client.auth.origin)

            run.phase = "preflight"
            assertTrue("Hermes memory is switched off for this profile; the test needs it on", readMemory(run.client).documents.any { it.enabled })
            // Notes an earlier stopped run of this test left behind would make the recall phases pass without any save.
            memoryTouched = true
            removeNotes(run) { (Mango.containsMatchIn(it) && FruitWords.containsMatchIn(it)) || (Pixel.containsMatchIn(it) && DogWord.containsMatchIn(it)) }
            run.baseline = readMemory(run.client).documents.flatMap { entries(it.content) }.toSet()
            val clean = run.baseline.joinToString("\n")
            assertFalse("The demo Hermes memory already mentions mango or Pixel; remove those notes so the test starts from nothing", Mango.containsMatchIn(clean) || Pixel.containsMatchIn(clean))

            // 1 + 2. The owner tells the call a fact: Hermes' own memory tool saves it, with no approval, and the assistant speaks as Hermes.
            run.phase = "1: voice saves"
            val save = callTurn(run, "save", SAVE_FRUIT, listOf(Mango), savesMemory = true)
            run.timings.put("voice_save_connect_ms", save.connectMs).put("voice_save_receipt_ms", save.memoryReceiptMs).put("voice_save_first_reply_ms", save.firstReplyMs)
            val memoryCalls = save.sightings.filter { it.receipt.toolName == "memory" }
            val completed = memoryCalls.filter { it.receipt.status == "completed" }
            assertTrue("Phase 1: the call never used Hermes' memory tool for \"$SAVE_FRUIT\" (tools seen: ${save.tools.ifBlank { "none" }}; reply: \"${save.reply}\")", memoryCalls.isNotEmpty())
            assertTrue("Phase 1: Hermes' memory tool did not complete on the call (${save.tools})", completed.isNotEmpty())
            assertTrue("Phase 1: the memory save returned no result (${save.tools})", completed.any { it.receipt.hasResult })
            assertTrue("Phase 1: the memory save asked the phone for approval (${save.tools}); Hermes saves a memory without asking", memoryCalls.none { it.receipt.status == "needs_approval" } && !save.approvalShown)
            assertTrue("Phase 1: the memory save was not allowed automatically as Hermes allows it (${save.tools})", completed.all { it.receipt.approvedBy == "memory" })
            eventually({ "Hermes memory to contain mango after the call (it reads: \"${readMemory(run.client).notes().take(300)}\")" }, 30_000) { Mango.containsMatchIn(readMemory(run.client).notes()) }
            run.passed.put("voice_saves_to_hermes_memory", true)
            run.phase = "2: speaks as Hermes"
            val distancing = SpeaksAsAnother.find(save.everythingSaid)
            assertNull("Phase 2: the assistant spoke of Hermes as someone else (\"${distancing?.value}\" in: \"${save.everythingSaid.take(400)}\")", distancing)
            run.passed.put("speaks_as_hermes", true)

            // 3. A new call recalls it.
            run.phase = "3: voice recalls"
            val recall = callTurn(run, "recall-fruit", ASK_FRUIT, listOf(Regex("(?i)\\bfruit\\b")), savesMemory = false)
            run.timings.put("voice_recall_connect_ms", recall.connectMs).put("voice_recall_first_reply_ms", recall.firstReplyMs)
            run.passed.put("voice_recall_tools", recall.tools)
            assertTrue("Phase 3: a new call did not recall the favourite fruit (it said: \"${recall.reply.take(300)}\"; tools: ${recall.tools.ifBlank { "none" }})", Mango.containsMatchIn(recall.reply))
            run.passed.put("voice_recalls_in_new_call", true)

            // 4. A new Hermes chat recalls it.
            run.phase = "4: chat recalls"
            openChat(run, "Memory recall in chat")
            val chatStarted = SystemClock.elapsedRealtime()
            val chatRecall = chatTurn(run, ASK_FRUIT)
            run.timings.put("chat_recall_ms", SystemClock.elapsedRealtime() - chatStarted)
            assertTrue("Phase 4: a new Hermes chat did not recall the favourite fruit (it said: \"${chatRecall.take(300)}\")", Mango.containsMatchIn(chatRecall))
            run.passed.put("chat_recalls_voice_fact", true)

            // 5. A fact told to a Hermes chat is recalled on a new call.
            run.phase = "5: chat saves"
            openChat(run, "Memory save in chat")
            val chatSaving = SystemClock.elapsedRealtime()
            val chatSaved = chatTurn(run, SAVE_DOG)
            run.timings.put("chat_save_ms", SystemClock.elapsedRealtime() - chatSaving)
            eventually({ "Hermes memory to contain Pixel after the chat message (the chat said: \"${chatSaved.take(300)}\"; memory reads: \"${readMemory(run.client).notes().take(300)}\")" }, 30_000) { Pixel.containsMatchIn(readMemory(run.client).notes()) }
            run.passed.put("chat_saves_to_hermes_memory", true)
            run.phase = "5: voice recalls"
            val dog = callTurn(run, "recall-dog", ASK_DOG, listOf(Regex("(?i)\\bdog\\b")), savesMemory = false)
            run.timings.put("voice_recall_dog_connect_ms", dog.connectMs).put("voice_recall_dog_first_reply_ms", dog.firstReplyMs)
            run.passed.put("voice_recall_dog_tools", dog.tools)
            assertTrue("Phase 5: a new call did not recall the dog's name from a Hermes chat (it said: \"${dog.reply.take(300)}\"; tools: ${dog.tools.ifBlank { "none" }})", Pixel.containsMatchIn(dog.reply))
            run.passed.put("voice_recalls_chat_fact", true)
            run.phase = "done"
        } catch (error: Throwable) { failure = error; throw error }
        finally { withContext(NonCancellable) {
            val cleanup = mutableListOf<Throwable>()
            suspend fun step(name: String, block: suspend () -> Unit) { try { block() } catch (error: Throwable) { cleanup += AssertionError("Cleanup: $name failed: ${error.message}", error) } }
            step("hang up") {
                app.calls.hangup(); withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) }
            }
            // 6. The test notes leave Hermes memory, through the Memory screen's save, and are shown to be gone.
            if (memoryTouched) step("remove the test notes from Hermes memory") {
                removeNotes(run) { Mango.containsMatchIn(it) || Pixel.containsMatchIn(it) }
                val left = readMemory(run.client)
                assertFalse("Phase 6: the test notes are still in Hermes memory", Mango.containsMatchIn(left.notes()) || Pixel.containsMatchIn(left.notes()))
                val now = left.documents.flatMap { entries(it.content) }.toSet()
                assertTrue("Phase 6: cleanup removed notes that were in Hermes memory before the test", now.containsAll(run.baseline))
                run.passed.put("test_notes_removed", true)
            }
            step("restore the voice selection") { voiceStore.setSelection(previousVoice) }
            run.calls.toList().forEach { call -> step("delete call record") { run.client.nativeRequest("$CALLS_PATH/$call", "DELETE") } }
            run.chats.toList().forEach { chat -> step("hide the test chat") { run.client.nativeRpc("session.set_hidden", json("session_id" to chat, "hidden" to true)) } }
            step("delete temporary audio") {
                val left = app.filesDir.listFiles { file -> file.name.startsWith("voice-memory-") && file.name.endsWith(".pcm") }.orEmpty().filterNot { it.delete() }
                run.passed.put("audio_files_removed", left.isEmpty())
                check(left.isEmpty()) { "Synthetic PCM fixture cleanup failed" }
            }
            step("close the client") { run.connected?.closeNative() }
            val original = failure
            publish(run, if (original == null && cleanup.isEmpty()) "passed" else "failed")
            if (cleanup.isNotEmpty()) {
                if (original == null) throw cleanup.first()
                cleanup.forEach { original.addSuppressed(it) }
            }
        } }
    }

    /**
     * Owner, 3 Nov: one multilingual agent. On one live Gemini call the owner speaks Arabic and is answered in Arabic, then switches
     * to English mid-call and is answered in English: nothing forces a language. Synthetic speech; no memory is touched.
     */
    @Test(timeout = 600_000) fun geminiFollowsTheOwnersLanguageMidCall() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        assumeTrue(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "The microphone grant is required (device-test.py --grant-mic)" }
        val voiceStore = VoiceKeyStore(app)
        require(voiceStore.hasKey(PROVIDER)) { "The Gemini key saved on the test phone is required to speak the fixture" }
        val arabicScript = Regex("[\\u0600-\\u06FF]")
        val latinWord = Regex("(?i)\\b[a-z]{2,}\\b")
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        val run = MemoryRun(app, vm)
        val previousVoice = voiceStore.settings()
        val arabic = spoken(app, "مرحبا، كيف حالك اليوم؟")
        val english = spoken(app, "Thanks. Tell me one short fun fact about the moon.")
        try {
            voiceStore.setSelection(voiceStore.settings(PROVIDER))
            eventually({ "the app to connect to the demo Hermes" }) { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }
            run.connected = requireNotNull(vm.client).detached()
            require(android.net.Uri.parse(run.client.auth.origin).port == 8446) { "This test runs only against the isolated demo Hermes" }
            openChat(run, "Voice language")
            rule.onNodeWithTag("chat-call-beacon").performClick()
            eventually({ "the call to start (error: ${app.calls.state.value.failureReason} ${app.calls.state.value.error})" }, 45_000) {
                app.calls.state.value.let { it.active && it.state in setOf("thinking", "listening", "speaking") }
            }
            run.calls += app.calls.state.value.callId
            assertEquals(PROVIDER, app.calls.state.value.voiceProvider)
            val service = requireNotNull(app.calls.service)
            service.mute(true)
            val pcm = read(BeaconCallService::class.java, service, "pcm") as NativePcm
            val direct = read(BeaconCallService::class.java, service, "directVoice") as DirectVoice
            withTimeoutOrNull(35_000) { while (app.calls.state.value.transcripts.none { it.role == "assistant" && it.final } && app.calls.state.value.error.isBlank()) delay(100) }
            drainSpeech(pcm)
            suspend fun turn(label: String, fixture: ByteArray, heard: Regex): String {
                val before = app.calls.state.value.transcripts.map { it.eventId }.toSet()
                fun fresh(role: String) = app.calls.state.value.transcripts.filter { it.role == role && it.eventId !in before && it.text.isNotBlank() }
                stream(direct, ByteArray(RATE * 2 * 600 / 1000)); stream(direct, fixture)
                stream(direct, ByteArray(RATE * 2 * 2200 / 1000)); endSpeech(direct)
                eventually({ "the $label speech to be heard (heard: \"${fresh("user").joinToString(" ") { it.text }}\")" }, 60_000) {
                    heard.containsMatchIn(fresh("user").filter { it.final }.joinToString(" ") { it.text })
                }
                eventually({ "an answer to the $label speech" }) { fresh("assistant").any { it.final } }
                drainSpeech(pcm); delay(1_000)
                return fresh("assistant").joinToString(" ") { it.text }
            }
            run.phase = "arabic"
            val inArabic = turn("Arabic", arabic, arabicScript)
            assertTrue("Spoken to in Arabic, the agent answered in Arabic (it said: \"${inArabic.take(300)}\")", arabicScript.containsMatchIn(inArabic))
            run.passed.put("answers_in_arabic", true)
            run.phase = "english"
            val inEnglish = turn("English", english, latinWord)
            assertTrue("After the owner switched to English mid-call, the agent answered in English (it said: \"${inEnglish.take(300)}\")",
                latinWord.findAll(inEnglish).count() >= 4 && !arabicScript.containsMatchIn(inEnglish))
            run.passed.put("follows_switch_to_english", true)
            run.phase = "done"
        } finally { withContext(NonCancellable) {
            arabic.fill(0); english.fill(0)
            runCatching { app.calls.hangup(); withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) } }
            runCatching { voiceStore.setSelection(previousVoice) }
            run.calls.toList().forEach { call -> runCatching { run.client.nativeRequest("$CALLS_PATH/$call", "DELETE") } }
            run.chats.toList().forEach { chat -> runCatching { run.client.nativeRpc("session.set_hidden", json("session_id" to chat, "hidden" to true)) } }
            runCatching { run.connected?.closeNative() }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("beacon_voice_language", run.passed.put("phase", run.phase).toString()) })
        } }
    }
}
