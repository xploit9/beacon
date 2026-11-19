package dev.hermes.android.voice

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The call fault suite (calls contract sections 9 and 11): one real Gemini Live call on the demo Hermes per scenario, with the
 * phone-to-Hermes link faulted by the host's fault_proxy.py, and the section 9 budgets asserted from what the phone saw. Run it
 * through `.unlazy/calls-121/tools/run_faults.py <scenario>` (emulator lease, proxy swap, staged demo sign-in, everything put back);
 * the test asks for each fault itself with an instrumentation status line (`calls_fault=<mode>:<seconds>:<delay ms>`), so it needs
 * nothing from the host's Hermes files. Opt-in: `beaconLiveService=1`. Every run writes files/calls-fault-<scenario>.json; the
 * assertions run after it is written. The model's words are never asserted on, only when it spoke and what the phone did.
 *
 * Scenarios: baseline (no fault), slow (fixed latency), hold (link stalled), drop (Hermes' answers lost), resultLost (an approved
 * read's answer lost), handover (a long request handed to Hermes), startUnreachable (no Hermes from the tap).
 * A "PRECONDITION:" failure means the model did not do what the scenario needs (for example no tool call); run it again.
 */
class CallsFaultDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    @Test(timeout = 600_000) fun baseline() = scenario("baseline")
    @Test(timeout = 600_000) fun slow() = scenario("slow")
    @Test(timeout = 600_000) fun hold() = scenario("hold")
    @Test(timeout = 600_000) fun drop() = scenario("drop")
    @Test(timeout = 600_000) fun resultLost() = scenario("resultLost")
    @Test(timeout = 600_000) fun handover() = scenario("handover")
    @Test(timeout = 600_000) fun startUnreachable() = scenario("startUnreachable")

    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val t0 = android.os.SystemClock.elapsedRealtime()
    private fun now() = android.os.SystemClock.elapsedRealtime() - t0
    private val out = JSONObject()
    private val measures = JSONObject()
    private val failures = mutableListOf<String>()
    private val samples = JSONArray()

    /** The first non-null [read] within [ms], checked every 50 ms. */
    private suspend fun <T : Any> poll(ms: Long, read: () -> T?): T? = withTimeoutOrNull(ms) { var value = read(); while (value == null) { delay(50); value = read() }; value }

    private fun budget(name: String, value: Long?, limit: Long) {
        measures.put(name, value ?: JSONObject.NULL).put("${name}_budget", limit)
        if (value == null || value > limit) failures += "$name=${value ?: "never"} (budget $limit)"
    }
    private fun precondition(text: String) { failures += "PRECONDITION: $text" }

    /** Asks the host for a fault now; returns the request time on the test clock. */
    private fun fault(mode: String, seconds: Int, delayMs: Int = 0): Long {
        val at = now()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("calls_fault", "$mode:$seconds:$delayMs") })
        out.put("fault", JSONObject().put("mode", mode).put("seconds", seconds).put("delay_ms", delayMs).put("requested_ms", at).put("ends_ms", at + seconds * 1000L))
        return at
    }

    /** The call's tool receipts as the call engine reported them, on the test clock. */
    private fun toolLog(): List<JSONObject> = app.calls.toolLog.map { JSONObject(it.toString()).put("t_ms", app.calls.tappedAt + it.optLong("elapsed_ms") - t0) }
    private fun stage(name: String): Long? = app.calls.stageLog.firstOrNull { it.optString("stage") == name }?.optLong("elapsed_ms")

    private suspend fun spoken(phrase: String, rate: Int): ByteArray {
        val credentials = requireNotNull(VoiceKeyStore(app).session("gemini")) { "A Gemini key on the test phone is required" }
        val http = okhttp3.OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            val source = withContext(Dispatchers.IO) {
                http.newCall(GeminiSpeech.request(credentials, phrase)).execute().use { response ->
                    check(response.isSuccessful) { "Fixture speech failed: ${response.code}" }
                    GeminiSpeech.pcm(JSONObject(requireNotNull(response.body).string()))
                }
            }
            val count = (source.size / 2L * rate / GeminiWire.OUTPUT_RATE).toInt()
            val output = ByteArray(count * 2)
            for (index in 0 until count) { val at = (index.toLong() * GeminiWire.OUTPUT_RATE / rate).toInt() * 2; output[index * 2] = source[at]; output[index * 2 + 1] = source[at + 1] }
            return output
        } finally { withContext(NonCancellable + Dispatchers.IO) { http.dispatcher.cancelAll(); http.connectionPool.evictAll() } }
    }

    private fun scenario(name: String) = runBlocking<Unit> {
        assumeTrue("Opt-in: run through run_faults.py (beaconLiveService=1, staged demo sign-in)", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        val app = app
        require(app.packageName.endsWith(".debug") && !app.calls.state.value.active)
        withContext(Dispatchers.Main.immediate) { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        out.put("scenario", name)
        val store = File(app.noBackupFilesDir, "call-start-diagnostics.json")
        val storeBefore = store.takeIf { it.isFile }?.readBytes()
        val voiceStore = VoiceKeyStore(app); val previousVoice = voiceStore.settings()
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms: Long = 90_000, condition: suspend () -> Boolean): Boolean = withTimeoutOrNull(ms) { while (!condition()) delay(100); true } == true
        var client: BeaconClient? = null; var session = ""; var call = ""
        try {
            voiceStore.setSelection(voiceStore.settings("gemini"))
            check(await { vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession }) { "The staged demo sign-in did not connect" }
            check(!vm.ui.value.setupWizard) { "The setup wizard opened over the chat" }
            val active = requireNotNull(vm.client).detached().also { client = it }
            rule.runOnIdle { vm.tab("Chat") }
            check(await { vm.ui.value.tab == "Chat" && !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) })
            val previousSession = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(liveTestChatBody(active, "Calls fault suite " + java.util.UUID.randomUUID().toString().take(8))) }.join()
            check(await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening })
            session = vm.chat.value.sessionId; testSession = session
            val target = active.scope.session(session)
            // A warm start is the owner's normal case: the Call button on screen keeps the call ready.
            // Compositions run on the compose test clock and recompose only when the test syncs: without frames the Call button
            // (and its prewarm hook) would see the new chat only at the click, and every call would start cold (14 Nov finding).
            out.put("warm", await(45_000) { runCatching { rule.mainClock.advanceTimeByFrame() }; app.calls.prewarm.ready(target) }).put("prewarm_status", app.calls.prewarm.status)
                .put("prewarm_sources", JSONArray(VoicePrewarm.sources().toList()))
            val ownerName = app.preferences.data.first().let(dev.hermes.android.data.AppPreferences::read).ownerName
            out.put("prewarm_chat_is_test_chat", VoicePrewarm.chatTarget()?.second == session)
                .put("prewarm_chat_present", VoicePrewarm.chatTarget() != null)
                .put("activity_state", rule.activity.lifecycle.currentState.name).put("window_focus", rule.activity.hasWindowFocus())
                .put("screen_interactive", app.getSystemService(android.os.PowerManager::class.java).isInteractive)
                .put("composer_chat_is_test_chat", vm.chat.value.sessionId == session)
                .put("prewarm_chat_is_previous_chat", previousSession.isNotBlank() && VoicePrewarm.chatTarget()?.second == previousSession)
                .put("prewarm_context_for_test_chat", app.calls.prewarm.context(target, ownerName) != null)
            // Which part of the call's target the warm session differs in, if any (true/false only, no values).
            runCatching { CallPrewarm::class.java.getDeclaredField("warm").apply { isAccessible = true }.get(app.calls.prewarm) as LiveSession? }.getOrNull()?.target?.let { t ->
                out.put("warm_target_matches", JSONObject().put("origin", t.origin == target.origin).put("owner", t.owner == target.owner)
                    .put("profile", t.profile == target.profile).put("session", t.session == target.session).put("native_provider", t.nativeProvider == target.nativeProvider))
            }
            out.put("takes_start_diagnostics", app.calls.startDiagnostics.takes(active.scope))
            when (name) {
                "startUnreachable" -> { fault("hold", 25); delay(700) }
                "slow" -> { fault("delay", 240, 400); delay(700) }
            }
            out.put("tap_ms", now()).put("prewarm_chat_is_test_chat_at_tap", VoicePrewarm.chatTarget()?.second == session).put("prewarm_ready_at_tap", app.calls.prewarm.ready(target))
            rule.onNodeWithTag("chat-call-beacon").performClick()
            val tapAt = now()
            val sampler = launch(Dispatchers.Default) {
                var last = ""
                while (isActive) {
                    val s = app.calls.state.value
                    val row = JSONObject().put("state", s.state).put("active", s.active).put("error", s.error).put("speaking", s.remoteSpeaking || s.state == "speaking")
                        .put("receipts", JSONArray(s.toolReceipts.map { "${it.requestId.take(8)} ${it.toolName.take(60)} ${it.status}" }))
                        .put("approvals", s.toolApprovals.size).put("assistant_lines", s.transcripts.count { it.final && it.role == "assistant" })
                    val sig = row.toString()
                    if (sig != last) { last = sig; samples.put(row.put("t_ms", now())) }
                    delay(100)
                }
            }
            if (name == "startUnreachable") { startUnreachable(tapAt, active.scope); sampler.cancelAndJoin() }
            else call = connectedCall(name, active, sampler)
        } catch (error: Throwable) {
            failures += "ERROR: " + error.javaClass.simpleName + ": " + error.message.orEmpty().take(200)
        } finally { withContext(NonCancellable) {
            out.put("measures", measures).put("failures", JSONArray(failures)).put("samples", samples)
                .put("warm_claimed", app.calls.stageLog.any { it.optBoolean("warm") })
            out.put("stage_log", JSONArray(app.calls.stageLog.map { JSONObject().put("stage", it.optString("stage")).put("elapsed_ms", it.optLong("elapsed_ms"))
                .put("error_code", it.optString("error_code")).put("failed_stage", it.optString("failed_stage")) }))
            out.put("tool_log", JSONArray(toolLog().map { JSONObject().put("t_ms", it.optLong("t_ms")).put("req", it.optString("request_id").take(8))
                .put("tool", it.optString("tool_name").take(60)).put("status", it.optString("status")).put("error_code", it.optString("error_code")).put("detail", it.optString("detail")) }))
            // What the model was sent and sent back, without audio (debug build): the frames that explained device round 2's repeats.
            runCatching { (BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(app.calls.service) as? DirectVoice)
                ?.let { out.put("frames", JSONArray(it.frameLog())) } }
            File(app.filesDir, "calls-fault-$name.json").writeText(out.toString(), Charsets.UTF_8)
            app.calls.hangup(); withTimeoutOrNull(60_000) { while (app.calls.service != null) delay(100) }
            if (call.isBlank()) call = connectedId
            runCatching { voiceStore.setSelection(previousVoice) }
            if (call.isNotBlank()) runCatching { withTimeoutOrNull(20_000) { client?.nativeRequest("/api/plugins/beacon/mobile/calls/$call", "DELETE") } }
            if (session.isNotBlank()) runCatching { withTimeoutOrNull(20_000) { client?.nativeRpc("session.set_hidden", json("session_id" to session, "hidden" to true)) } }
            // The rows this run's starts left on the phone go with it.
            runCatching { if (storeBefore == null) store.delete() else store.writeBytes(storeBefore) }
            client?.closeNative()
        } }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The test's own chat. */
    @Volatile private var testSession = ""

    /** The call's id once connected (cleanup deletes it even when the scenario failed part way). */
    @Volatile private var connectedId = ""

    /** Connects, lets the greeting finish, speaks the scenario's request into the call and runs the scenario; returns the call id. */
    private suspend fun connectedCall(name: String, client: BeaconClient, sampler: Job): String {
        val app = app
        suspend fun await(ms: Long, condition: suspend () -> Boolean): Boolean = withTimeoutOrNull(ms) { while (!condition()) delay(100); true } == true
        run {
            check(await(45_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() && app.calls.state.value.state in setOf("thinking", "listening", "speaking") }) { "The call did not connect" }
            val call = app.calls.state.value.callId; connectedId = call; out.put("call", call.take(8)).put("connected_ms", now())
                .put("call_conversation_is_test_chat", app.calls.state.value.conversationScope?.session == testSession)
                .put("provider_setup_warm", app.calls.stageLog.firstOrNull { it.optString("stage") == "provider_setup_ok" }?.optBoolean("warm") ?: JSONObject.NULL)
                .put("adopted_prewarmed_session", app.calls.stageLog.firstOrNull { it.optString("stage") == "provider_open" }?.has("warm") == true)
            val service = requireNotNull(app.calls.service)
            val pcm = BeaconCallService::class.java.getDeclaredField("pcm").apply { isAccessible = true }.get(service) as NativePcm
            service.mute(true)
            val direct = BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(service) as DirectVoice
            await(35_000) { app.calls.state.value.transcripts.any { it.role == "assistant" && it.final } }
            out.put("greeted_ms", now())
            // The greeting finishes playing before the owner speaks.
            val queue = NativePcm::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(pcm) as PcmQueue
            val ledger = NativePcm::class.java.getDeclaredField("ledger").apply { isAccessible = true }.get(pcm) as PlaybackLedger
            val lock = NativePcm::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(pcm)
            val bytes = PcmQueue::class.java.getDeclaredField("bytes").apply { isAccessible = true }
            var quiet = 0L
            await(30_000) {
                val empty = synchronized(lock) { synchronized(queue) { bytes.getInt(queue) == 0 } && ledger.pendingBytes == 0 }
                if (!empty) { quiet = 0; false } else { val t = System.nanoTime(); if (quiet == 0L) quiet = t; t - quiet > 1_500_000_000L }
            }
            val rate = 16000
            val fixture = spoken(if (name == "handover") LONG_REQUEST else READ_REQUEST, rate)
            suspend fun stream(b: ByteArray) {
                var offset = 0
                while (offset < b.size) {
                    val end = minOf(offset + rate * 2 / 50, b.size); val piece = b.copyOfRange(offset, end)
                    withTimeout(5000) { while (!direct.pcm(piece) { true }) delay(5) }
                    offset = end; delay(20)
                }
            }
            out.put("speech_start_ms", now())
            stream(ByteArray(rate * 2 * 600 / 1000)); stream(fixture)
            // The model often acts during the silence after the words, so a link fault starts as soon as they are said.
            if (name == "hold" || name == "drop") linkFaultAt = fault(name, 45)
            stream(ByteArray(rate * 2 * 2200 / 1000))
            fixture.fill(0)
            direct.send(GeminiWire.endAudio())
            val spokeAt = now(); out.put("speech_end_ms", spokeAt)
            when (name) {
                "baseline", "slow" -> readTool(call, client, spokeAt, slow = name == "slow")
                "hold", "drop" -> linkFault(name, spokeAt)
                "resultLost" -> resultLost(call, spokeAt)
                "handover" -> handover(call, client, spokeAt)
            }
            sampler.cancelAndJoin()
            out.put("ended_ms", now())
            return call
        }
    }

    /** Approves the read cards this suite's request asks for (the demo's session search), the way the owner's Full access would. */
    private suspend fun approveReads(approved: MutableMap<String, Long>, call: String) {
        for (card in app.calls.state.value.toolApprovals.filter { it.requestId !in approved && it.toolName.contains("session_search") }) {
            approved[card.requestId] = now()
            withContext(Dispatchers.Main.immediate) { app.calls.decideTool(call, card.requestId, card.proposalId, "approve") }
        }
    }

    /** Tool receipts of [request] at or after [from]: the first that tells the model where it stands, and the first final one. */
    private fun told(request: String, from: Long, statuses: Set<String> = TOLD) = toolLog().firstOrNull { it.optString("request_id") == request && it.optLong("t_ms") >= from && it.optString("status") in statuses }?.optLong("t_ms")

    private suspend fun readTool(call: String, client: BeaconClient, spokeAt: Long, slow: Boolean) {
        val approved = mutableMapOf<String, Long>()
        val deadline = now() + 60_000
        var done: JSONObject? = null
        while (now() < deadline && done == null) {
            approveReads(approved, call)
            // A gateway tool if one ran, else any Hermes read the model used (its own search, memory or skills).
            done = toolLog().lastOrNull { it.optString("status") == "completed" && it.optString("tool_name") !in FIXED_LOOKUPS }
                ?: toolLog().lastOrNull { it.optString("status") == "completed" && it.optString("tool_name") !in setOf("discover_tools", "tool_status") }?.takeIf { now() > deadline - 45_000 }
            delay(100)
        }
        delay(8_000) // the model's answer
        stage("grant_ok")?.let { measures.put("grant_ok_ms", it) }
        stage("first_model_audio")?.let { measures.put("first_model_audio_ms", it).put("first_model_audio_target_ms", 1_000) }
        if (!slow) { budget("grant_ok_ms", stage("grant_ok"), 600); budget("first_model_audio_ms", stage("first_model_audio"), 2_000) }
        val finished = done ?: run { precondition("no Hermes tool completed within 60 s of the request"); return }
        val request = finished.getString("request_id")
        val firstSeen = toolLog().first { it.optString("request_id") == request }.optLong("t_ms")
        val endToEnd = finished.optLong("t_ms") - maxOf(firstSeen, approved[request] ?: 0L)
        measures.put("tool", finished.optString("tool_name").take(60)).put("tool_first_seen_ms", firstSeen - spokeAt)
        // Hermes' execute time, as its v3 receipt gave it to the call (the call's tool log keeps it); this test's own client reads the
        // request without the v3 contract header, and that older answer has no server_ms (device round 1).
        val execute = toolLog().lastOrNull { it.optString("request_id") == request && it.has("execute_ms") }?.optDouble("execute_ms")?.toLong()
        if (slow) {
            measures.put("tool_end_to_end_ms", endToEnd)
            val alarms = toolLog().filter { it.optString("status") in setOf("hermes_unreachable", "lost_result") }
            if (alarms.isNotEmpty()) failures += "a slow but working link was reported lost: ${alarms.map { it.optString("status") }}"
        } else if (execute != null) budget("tool_end_to_end_ms", endToEnd, execute + 500)
        else { measures.put("tool_server_ms", "not reported (v2 plugin)"); budget("tool_end_to_end_ms", endToEnd, 2_000) }
    }

    /** When the hold or drop scenario's fault was asked for (right after the spoken words). */
    private var linkFaultAt = 0L

    private suspend fun linkFault(mode: String, spokeAt: Long) {
        val faultAt = linkFaultAt
        val faultEnd = faultAt + 45_000
        val approved = mutableMapOf<String, Long>()
        val call = app.calls.state.value.callId
        while (now() < faultEnd + 30_000) { approveReads(approved, call); delay(200) }
        // The first tool call made or still pending once the link failed.
        val pendingAtFault = toolLog().groupBy { it.optString("request_id") }.filterValues { entries ->
            val first = entries.minOf { it.optLong("t_ms") }
            val finalBefore = entries.any { it.optString("status") in FINAL && it.optLong("t_ms") < faultAt }
            !finalBefore && first < faultEnd
        }
        val request = pendingAtFault.minByOrNull { (_, entries) -> entries.minOf { it.optLong("t_ms") } }?.key
            ?: run { precondition("no tool call while the link was down"); return }
        val firstSeen = pendingAtFault.getValue(request).minOf { it.optLong("t_ms") }
        val from = maxOf(faultAt, firstSeen)
        measures.put("tool_first_seen_after_fault_ms", firstSeen - faultAt)
        budget("told_after_fault_ms", told(request, from)?.let { it - from }, 5_000)
        measures.put("final_after_link_ms", told(request, faultEnd, FINAL)?.let { it - faultEnd } ?: JSONObject.NULL)
        val stuck = toolLog().filter { it.optString("request_id") == request }.map { it.optString("status") }
        measures.put("statuses", JSONArray(stuck))
    }

    private suspend fun resultLost(call: String, spokeAt: Long) {
        val card = poll(45_000) { app.calls.state.value.toolApprovals.firstOrNull { it.toolName.contains("session_search") } }
            ?: run { precondition("no session search card within 45 s"); delay(5_000); return }
        val faultAt = fault("drop", 45)
        val faultEnd = faultAt + 45_000
        delay(700)
        val approvedAt = now()
        withContext(Dispatchers.Main.immediate) { app.calls.decideTool(call, card.requestId, card.proposalId, "approve") }
        // What the owner sees on the call screen while the answer is lost: the screen as drawn 8 s into the fault.
        while (now() < faultAt + 8_000) { runCatching { rule.mainClock.advanceTimeByFrame() }; delay(100) }
        runCatching {
            val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(app.filesDir, "calls-fault-resultLost.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
            measures.put("screenshot_ms_after_fault", now() - faultAt).put("tool_window_at_screenshot", JSONArray(app.calls.state.value.toolReceipts.map { "${it.toolName.take(40)} ${it.status}" }))
        }
        while (now() < faultEnd + 30_000) delay(200)
        measures.put("approved_after_fault_ms", approvedAt - faultAt)
        budget("told_after_fault_ms", told(card.requestId, approvedAt)?.let { it - faultAt }, 5_000)
        budget("final_after_link_ms", told(card.requestId, faultEnd, setOf("completed"))?.let { it - faultEnd }, 5_000)
        measures.put("statuses", JSONArray(toolLog().filter { it.optString("request_id") == card.requestId }.map { it.optString("status") }))
    }

    private suspend fun handover(call: String, client: BeaconClient, spokeAt: Long) {
        val approved = mutableMapOf<String, Long>()
        val deadline = now() + 50_000
        while (now() < deadline) { approveReads(approved, call); delay(200) }
        // Nobody said yes to a call back, so none may be set.
        val jobs = runCatching { withTimeoutOrNull(10_000) { client.nativeRequest("/api/plugins/beacon/mobile/calls/$call") }?.optJSONArray("jobs") }.getOrNull()
        val requested = (0 until (jobs?.length() ?: 0)).count { jobs!!.optJSONObject(it)?.optString("callback_consent") == "requested" }
        measures.put("jobs", jobs?.length() ?: JSONObject.NULL).put("callbacks_requested", requested)
        if (requested > 0) failures += "a call back was set without the owner's yes"
        val work = toolLog().firstOrNull { it.optString("tool_name") == "hermes_work" }
            ?: run {
                precondition(if ((jobs?.length() ?: 0) > 0) "Hermes has the job, but the call engine reported no hermes_work receipt (its time is not observable)"
                    else "the model did not hand the long request to Hermes within 50 s")
                return
            }
        val calledAt = work.optLong("t_ms")
        measures.put("hermes_work_after_speech_ms", calledAt - spokeAt)
        // The owner is told in the same turn as the call: speech that started up to 3 s before it, or within the budget after it.
        val speaking = (0 until samples.length()).map { samples.getJSONObject(it) }.filter { it.optBoolean("speaking") }.map { it.optLong("t_ms") }
        val toldAt = speaking.firstOrNull { it >= calledAt - 3_000 }
        budget("owner_told_after_handover_ms", toldAt?.let { maxOf(0L, it - calledAt) }, 2_000)
    }

    private suspend fun startUnreachable(tapAt: Long, account: Scope) {
        val line = dev.hermes.android.push.serviceText(app, APP_CONNECTION_ERROR)
        val shown = poll(15_000) { app.calls.state.value.takeIf { !it.active && it.error.isNotBlank() }?.let { now() } }
        budget("reach_line_ms", shown?.let { it - tapAt }, 4_000)
        val error = app.calls.state.value.error
        measures.put("line_is_reach_line", error.contains(line))
        if (!error.contains(line)) failures += "the line was not the reach line: ${error.take(120)}"
        measures.put("reach_failed_stage", app.calls.stageLog.any { it.optString("stage") == "reach_failed" })
        delay(500)
        val row = app.calls.startDiagnostics.pending(account).lastOrNull()
        if (row == null || row.optString("outcome") != "failed" || row.optString("shown") != "hermes_unreachable" || row.optString("failed_stage") != "grant_ok")
            failures += "no failed start row on the phone: ${row?.toString()?.take(300)}"
        else measures.put("row_stages", row.getJSONArray("stages").length())
        val faultEnd = out.getJSONObject("fault").getLong("ends_ms")
        if (app.calls.startDiagnostics.takes(account)) {
            val id = row?.optString("client_event_id")
            val stored = withTimeoutOrNull(faultEnd - now() + 20_000) { while (app.calls.startDiagnostics.pending(account).any { it.optString("client_event_id") == id }) delay(200); now() }
            budget("diagnostics_stored_after_link_ms", stored?.let { maxOf(0L, it - faultEnd) }, 10_000)
        } else {
            measures.put("diagnostics", "kept on the phone: this Hermes does not take start diagnostics (v2)")
            delay((faultEnd - now()).coerceAtLeast(0) + 2_000)
        }
    }

    private companion object {
        const val READ_REQUEST = "Please search my past conversations for anything about invoices and tell me what you find."
        const val LONG_REQUEST = "I need a long multi-step job: research three good Italian restaurants near the office, compare their reviews and opening hours, and write me a short summary in the chat."
        val FIXED_LOOKUPS = setOf("discover_tools", "hermes_search", "hermes_memory", "hermes_skills", "hermes_context", "tool_status")
        val FINAL = setOf("completed", "failed", "denied", "expired", "cancelled", "uncertain", "unavailable", "not_sent", "submission_unconfirmed")
        /** What tells the model where a pending tool stands: a link state (calls contract section 6) or a final status. */
        val TOLD = FINAL + setOf("hermes_unreachable", "still_running", "lost_result")
    }
}
