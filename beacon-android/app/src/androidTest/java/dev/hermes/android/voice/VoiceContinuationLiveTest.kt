package dev.hermes.android.voice

import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BuildConfig
import dev.hermes.android.liveTestChatBody
import dev.hermes.android.preferences
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.journey.OwnerJourneyHarness
import dev.hermes.android.journey.ownerRuntimeOptIn
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.parseKeepConnectedPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Root-only real Grok/native continuation journey. Diagnostics contain no conversation or credential payloads. */
class VoiceContinuationLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    private var verifiedScope: Scope? = null
    private var verifiedVersions = false
    private var expectedDevice = ""
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("beaconVoiceContinuationLive") == "1")
            try {
                check(args.getString("beaconOwnerRuntime") == "1" && args.getString("beaconLiveService") == "1" && args.getString("beaconNoHostAudio") == "1")
                val profile = args.getString("beaconQaProfile").orEmpty()
                val expectedOrigin = args.getString("beaconVoiceContinuationOriginSha256").orEmpty()
                check(profile.matches(Regex("qa-[0-9a-f]{32}")) && expectedOrigin.matches(Regex("[0-9a-f]{64}")))
                expectedDevice = args.getString("beaconVoiceContinuationDevice").orEmpty()
                check(args.getString("beaconDemoExclusiveRecipient") == "1" && expectedDevice.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
                runBlocking {
                    val origin = app.preferences.data.first()[stringPreferencesKey("origin")].orEmpty()
                    check(origin.isNotBlank() && trustedOrigin(origin).digest() == expectedOrigin)
                    val auth = app.auth.read(origin)
                    val scope = auth.cachedScopeFor(origin, replacingAccount = false)
                    check(scope != null && scope.profile == profile && scope.origin.digest() == expectedOrigin)
                    verifiedScope = scope
                    val read = BeaconClient(auth, app.auth, persistContext = false)
                    try {
                        val versions = read.nativeRequest("/api/plugins/beacon/mobile/plugin/version")
                        val expected = BuildConfig.VERSION_NAME.removeSuffix("-preview")
                        val actual = app.packageManager.getPackageInfo(app.packageName, 0)
                        check(listOf("running", "installed", "gateway").all { versions.string(it) == expected } && actual.versionName == BuildConfig.VERSION_NAME && actual.longVersionCode == BuildConfig.VERSION_CODE.toLong())
                        verifiedVersions = true
                    } finally { read.closeNative() }
                }
                base.evaluate()
            } catch (skip: AssumptionViolatedException) { throw skip }
            catch (error: Throwable) {
                val detail = (error.message ?: "").replace(Regex("https?://\\S+"), "[url]").take(160)
                throw AssertionError("voice_continuation_preflight_or_journey_failed: " + error.javaClass.simpleName +
                    (if (detail.isBlank()) "" else ": " + detail))
            }
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(optIn).around(compose)

    @Test(timeout = 600_000) fun realNativeTaskSurvivesHangupAndItsConsentedCallbackArrives() = runBlocking {
        val h = OwnerJourneyHarness(compose, "voice-continuation")
        h.checked {
            val evidence = json("run" to runId, "phase" to "preflight", "status" to "preflight", "cleanup_pending" to true, "conversation_deleted" to false, "demo_exclusive_recipient_attested" to true,
                "app_version" to BuildConfig.VERSION_NAME, "app_version_code" to BuildConfig.VERSION_CODE,
                "baseline_source_manifest" to "b78aff230f0a676d463fbeb9572d858b2974a2912eae047756b022de54948e73")
            val output = AtomicFile(File(app.filesDir, "voice-continuation-live.json"))
            fun save(step: String) {
                evidence.put("phase", step)
                val stream = output.startWrite()
                try { stream.write(evidence.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
                catch (error: Throwable) { output.failWrite(stream); throw error }
            }
            fun id(name: String, value: String) {
                check(value.matches(Regex("[A-Za-z0-9_.:-]{1,256}")))
                evidence.put(name, value)
            }
            save("startup_selection")
            await(60_000) { !vm.ui.value.loading && !vm.ui.value.restoringSession && !vm.ui.value.newChatOpening }
            withTimeout(60_000) { withContext(Dispatchers.Main.immediate) { vm.awaitSelection() } }
            save("scope_guard"); check(scope == verifiedScope && verifiedVersions)
            save("qa_guard"); qa()
            save("draft_guard"); check(vm.composer.value.text.isBlank() && vm.attachments.value.isEmpty())
            val voice = VoiceKeyStore(app)
            val previousVoice = voice.settings()
            save("voice_guard"); check(voice.hasKey("grok"))
            suspend fun recipient(sourceHint: String? = null): String? {
                current(); save("recipient_status")
                val status = client.nativeRequest("/api/plugins/beacon/mobile/notifications/status")
                val targets = status.rows("targets").filter { !it.optBoolean("revoked") && it.optLong("expires") > System.currentTimeMillis() / 1000 }
                val device = NativePairing.deviceId(app, scope)
                evidence.put("active_fcm_targets", targets.size); save("recipient_device")
                check(device != null && device == expectedDevice && NativePairing.deviceHeader("/api/plugins/beacon/mobile/calls", scope) == device)
                val source = if (targets.isNotEmpty()) {
                    save("recipient_fcm")
                    check(status.opt("android_configured") == true && targets.size == 1)
                    val target = targets.single()
                    check(target.string("kind") == "fcm" && target.string("profile") == scope.profile && target.string("target_id") == app.push.registeredTarget(scope))
                    id("qa_target_id", target.getString("target_id")); evidence.put("local_delivery", false)
                    target.string("source_ref")
                } else {
                    save("recipient_local_enabled")
                    val serviceStart = SystemClock.elapsedRealtime()
                    var serviceReady = false
                    var lastServiceStatus = ""
                    try {
                        await(45_000) {
                            current()
                            val enabled = KeepConnectedService.isEnabledFor(app, scope)
                            val serviceStatus = KeepConnectedService.status(app)
                            val safeStatus = serviceStatus.takeIf { it in setOf(KeepConnectedService.STATUS_OFF, KeepConnectedService.STATUS_STARTING, KeepConnectedService.STATUS_CONNECTED, KeepConnectedService.STATUS_RECONNECTING, KeepConnectedService.STATUS_SIGNED_OUT) } ?: "other"
                            evidence.put("local_service_enabled", enabled).put("local_service_status", safeStatus)
                            if (!enabled || safeStatus != lastServiceStatus) {
                                lastServiceStatus = safeStatus
                                save(if (enabled) "recipient_local_wait" else "recipient_local_disabled")
                            }
                            check(enabled) { "local_service_disabled" }
                            serviceReady = serviceStatus == KeepConnectedService.STATUS_CONNECTED
                            serviceReady
                        }
                    } finally {
                        evidence.put("local_service_wait_ms", SystemClock.elapsedRealtime() - serviceStart).put("local_service_ready", serviceReady)
                        save(if (serviceReady) "recipient_local_ready" else if (evidence.opt("local_service_enabled") == false) "recipient_local_disabled" else "recipient_local_wait")
                    }
                    val devices = NativePairing.devices(client)
                    val matching = devices.count { it.id == device }
                    evidence.put("registered_device_count", devices.size).put("matching_registered_device_count", matching).put("current_device_registered", matching == 1); save("recipient_local_devices")
                    check(matching == 1)
                    save("recipient_local_source")
                    evidence.put("local_delivery", true)
                    // A fresh profile can have no prior notices; the real callback supplies its source.
                    sourceHint
                }
                if (source == null) {
                    check(targets.isEmpty()); current()
                    evidence.put("recipient_source_pending", true); id("qa_device_id", device)
                    return null
                }
                save("recipient_binding")
                val binding = checkNotNull(app.push.binding(source))
                check(binding.string("origin") == scope.origin && binding.string("owner") == scope.owner && binding.string("profile") == scope.profile &&
                    binding.string("native_provider") == scope.nativeProvider)
                if (targets.isEmpty()) check(binding.opt("keep_connected") == true && binding.string("target").isBlank() && (binding.string("device_id").isBlank() || binding.string("device_id") == device))
                else check(binding.string("target") == targets.single().string("target_id") && binding.string("device_id") == device)
                save("recipient_permission"); check(app.push.permitsIncoming(source)); current()
                evidence.put("active_fcm_targets", targets.size)
                id("qa_source_ref", source); id("qa_device_id", device)
                return source
            }
            val initialSource = recipient()
            evidence.put("status", "running").put("recipient_transport_verified", true).put("recipient_verified", initialSource != null).put("origin_verified", true).put("profile_verified", true).put("versions_matched", true)
            save("preflight_passed")
            var session = ""; var call = ""; var callback = ""; var started = false; var complete = false
            val ownedCalls = mutableSetOf<String>()
            suspend fun callState(callId: String): JSONObject {
                current()
                return withTimeout(15_000) { client.nativeRequest("/api/plugins/beacon/mobile/calls/$callId") }.also {
                    check(it.string("call_id") == callId && it.string("profile") == scope.profile && it.string("conversation_id") == session); current()
                }
            }
            suspend fun stopped() {
                await(45_000) { !app.calls.state.value.active && app.calls.service == null && !app.calls.state.value.audioStopPending }
            }
            suspend fun receipt(job: JSONObject, result: Boolean = false): JSONObject {
                val read = client.detached()
                try {
                    save("native_receipt_identity")
                    current()
                    val runtime = job.string("runtime_session_id")
                    check(runtime == session && job.string("stored_session_id") == session)
                    save("native_receipt_connection")
                    val binding = read.nativeSession(session)
                    check(binding.scope == scope && binding.storedSessionId == session && binding.runtimeSessionId == runtime)
                    check(read.nativeTurnLease(session, runtime).authority)
                    val info = read.nativeSessionSnapshot().getJSONObject("info")
                    save("native_receipt_lookup")
                    val value = read.nativeRpc("prompt.receipt", json("session_id" to runtime, "admission_id" to job.getString("admission_id"), "include_result" to result))
                    val ref = checkNotNull(value.optJSONObject("ref"))
                    evidence.put("receipt_profile_name_matches", info.string("profile_name") == scope.profile)
                        .put("receipt_profile_id_matches", ref.string("profile_id") == info.string("profile_id"))
                    save("native_receipt_scope")
                    check(info.string("profile_name") == scope.profile && info.string("profile_id").isNotBlank() && ref.string("profile_id") == info.string("profile_id"))
                    check(ref.string("session_id") == session && value.string("admission_id") == job.string("admission_id")); current()
                    return value
                } catch (error: Throwable) {
                    evidence.put("receipt_failure_type", error.javaClass.simpleName)
                    (error as? ApiFailure)?.let { evidence.put("receipt_failure_code", it.status) }
                    save(evidence.getString("phase")); throw error
                } finally { read.closeNative() } // Never keep a test-created native viewer alive across hangup.
            }
            fun approvals(job: JSONObject?) {
                val count = app.calls.state.value.toolApprovals.size + if (vm.chat.value.approval == null) 0 else 1
                if (count > 0 || job?.string("status") == "needs_input") {
                    evidence.put("approval_count", count).put("approval_required", true)
                    app.calls.state.value.toolApprovals.firstOrNull()?.requestId?.takeIf(String::isNotBlank)?.let { id("approval_request_id", it) }
                    vm.chat.value.approval?.string("id")?.takeIf(String::isNotBlank)?.let { id("native_approval_id", it) }
                    job?.string("tool_request_id")?.takeIf(String::isNotBlank)?.let { id("tool_request_id", it) }
                    save("approval_required")
                }
            }
            try {
                voice.setSelection(voice.settings("grok"))
                val previous = vm.chat.value.sessionId
                val chatBody = liveTestChatBody(client, "Voice continuation $runId")
                withContext(Dispatchers.Main.immediate) { vm.newChat(chatBody) }.join()
                withTimeout(60_000) { withContext(Dispatchers.Main.immediate) { vm.awaitSelection() } }
                await(60_000) { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
                session = vm.chat.value.sessionId; id("qa_session_id", session)
                check(vm.authenticatedScope() == scope.session(session))
                save("native_draft_snapshot")
                val draft = client.detached()
                try {
                    current()
                    val binding = draft.nativeSession(session)
                    check(binding.scope == scope && binding.storedSessionId == session && binding.runtimeSessionId == session)
                    check(draft.nativeTurnLease(session, session).authority)
                    val snapshot = draft.nativeSessionSnapshot()
                    check(nativeSessionBinding(scope, snapshot) == binding && snapshot.opt("running") == false)
                    val messages = snapshot.getJSONArray("messages")
                    check(snapshot.opt("message_count") is Number && snapshot.optDouble("message_count") == messages.length().toDouble() && snapshot.rows("messages").size == messages.length())
                    check(snapshot.rows("messages").none { it.string("role") == "user" } && snapshot.getJSONArray("pending").length() == 0 && snapshot.getJSONArray("prompts").length() == 0)
                    current(); check(vm.authenticatedScope() == scope.session(session))
                } finally { draft.closeNative() }
                evidence.put("native_draft_verified", true)
                save("chat_created"); check(recipient(initialSource) == initialSource)
                started = true
                withContext(Dispatchers.Main.immediate) { app.calls.start(client, session) }
                await(45_000) {
                    app.calls.service?.mute(true)
                    app.calls.state.value.let { it.active && it.callId.isNotBlank() && it.voiceProvider == "grok" && it.conversationScope == scope.session(session) && it.state in setOf("thinking", "listening", "speaking") } &&
                        app.calls.stageLog.any { it.string("stage") == "provider_ready" && it.string("provider") == "grok" }
                }
                call = app.calls.state.value.callId; ownedCalls += call; id("qa_call_id", call)
                check(app.calls.state.value.muted); save("call_ready")
                val wireStates = org.json.JSONArray()
                fun providerProgress(): Boolean = runCatching {
                    val service = checkNotNull(app.calls.service)
                    val direct = BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }.get(service) as DirectVoice
                    val socket = checkNotNull(DirectVoice::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(direct))
                    val listener = socket.javaClass.declaredFields.first { okhttp3.WebSocketListener::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }.get(socket)
                    val wire = listener.javaClass.declaredFields.first { it.type == GrokWire::class.java }.apply { isAccessible = true }.get(listener) as GrokWire
                    val snapshot = synchronized(wire) {
                        json("responding" to GrokWire::class.java.getDeclaredField("responding").apply { isAccessible = true }.getBoolean(wire),
                            "pending" to GrokWire::class.java.getDeclaredField("responsePending").apply { isAccessible = true }.getBoolean(wire),
                            "awaiting_tools" to (GrokWire::class.java.getDeclaredField("awaiting").apply { isAccessible = true }.get(wire) as Collection<*>).size)
                    }
                    val lock = DirectVoice::class.java.getDeclaredField("greetingLock").apply { isAccessible = true }.get(direct)
                    val complete = synchronized(lock) { DirectVoice::class.java.getDeclaredField("greetingComplete").apply { isAccessible = true }.getBoolean(direct) }
                    snapshot.put("greeting_complete", complete)
                    if (wireStates.length() < 16 && (wireStates.length() == 0 || wireStates.getJSONObject(wireStates.length() - 1).toString() != snapshot.toString())) wireStates.put(snapshot)
                    evidence.put("provider_state_samples", wireStates).put("provider_state_available", true)
                    complete
                }.getOrElse { evidence.put("provider_state_available", false); false }
                await(45_000) {
                    val complete = providerProgress(); save("initial_greeting")
                    complete && app.calls.state.value.transcripts.any { it.role == "assistant" && it.final && !it.interrupted } &&
                        app.calls.stageLog.any { it.string("stage") == "playout_started" } && !app.calls.state.value.remoteSpeaking
                }
                evidence.put("initial_greeting_final", true); save("initial_greeting_complete")
                val marker = "BEACON_CONTINUATION_" + runId.replace("-", "")
                val request = "Use hermes_work for exactly one task in this same chat. Run only a harmless bounded terminal delay of 60 seconds (python -c \"import time; time.sleep(60)\"). Then return a short draft note containing $marker. Continue after I hang up. I consent to one callback only when this task is done. Do not send emails or messages, edit files, make purchases, or dispatch duplicate work."
                check(app.calls.sendText(call, request)); evidence.put("typed_turns", 1); save("request_sent")
                var admitted: JSONObject? = null
                await(120_000) {
                    delay(750)
                    providerProgress()
                    evidence.put("observed_tool_receipts", org.json.JSONArray(app.calls.state.value.toolReceipts.map {
                        json("tool" to it.toolName.take(80), "status" to it.status.take(40), "has_result" to it.hasResult)
                    }))
                    save("request_sent")
                    val jobs = callState(call).rows("jobs"); check(jobs.size <= 1)
                    val job = jobs.singleOrNull()
                    if (job != null) {
                        id("qa_job_id", job.getString("job_id")); id("native_request_id", job.getString("request_id"))
                        job.string("admission_id").takeIf(String::isNotBlank)?.let { id("native_admission_id", it) }
                        evidence.put("native_job_status", job.string("status").takeIf { it in setOf("submission_unconfirmed", "queued", "working", "needs_input", "done", "failed", "cancelled", "uncertain") } ?: "other")
                        if (job.string("status") in setOf("done", "failed", "cancelled")) { save("ended_before_hangup"); error("job_not_unfinished") }
                    }
                    approvals(job)
                    if (job != null && job.string("admission_id").isNotBlank() && job.string("status") in setOf("queued", "working", "needs_input")) {
                        val native = receipt(job)
                        if (native.string("status") in setOf("queued", "started") && job.opt("will_continue") == true &&
                            job.string("callback_consent") == "requested") admitted = job
                    }
                    admitted != null
                }
                val job = checkNotNull(admitted)
                check(job.string("callback_consent") == "requested")
                id("qa_job_id", job.getString("job_id")); id("native_request_id", job.getString("request_id")); id("native_admission_id", job.getString("admission_id"))
                evidence.put("durable_admission", true); save("durable_admission")
                check(receipt(job).string("status") in setOf("queued", "started"))
                withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
                stopped(); check(callState(call).opt("closed") == true)
                val after = callState(call).rows("jobs").single()
                check(after.string("job_id") == job.string("job_id") && after.string("status") in setOf("queued", "working", "needs_input") && after.opt("will_continue") == true)
                check(receipt(after).string("status") in setOf("queued", "started"))
                evidence.put("audio_service_ended", true).put("continued_after_hangup", true); save("continued_after_hangup")
                var finished: JSONObject? = null
                await(240_000) {
                    delay(1000)
                    val jobs = callState(call).rows("jobs"); check(jobs.size == 1)
                    val currentJob = jobs.single(); check(currentJob.string("job_id") == job.string("job_id")); approvals(currentJob)
                    check(currentJob.string("status") !in setOf("failed", "cancelled"))
                    if (currentJob.string("status") == "done") finished = currentJob
                    finished != null
                }
                val done = checkNotNull(finished)
                var terminalReceipt: JSONObject? = null
                await(30_000) { delay(500); val value = receipt(done, result = true); if (value.string("status") == "terminal") terminalReceipt = value; terminalReceipt != null }
                val committed = checkNotNull(terminalReceipt); val result = checkNotNull(committed.optJSONObject("result"))
                check(committed.string("status") == "terminal" && committed.string("outcome") == "completed" && !result.optBoolean("failed") && !result.optBoolean("interrupted") && result.opt("completed") != false && result.string("error").isBlank())
                check(result.string("final_response").contains(marker) && done.string("result").contains(marker) && done.string("callback_consent") == "requested")
                evidence.put("native_committed_success", true); save("native_success")
                await(30_000) {
                    delay(500)
                    val read = client.detached()
                    try {
                        val binding = read.nativeSession(session); check(binding.scope == scope && binding.storedSessionId == session && binding.runtimeSessionId == session)
                        val snapshot = read.nativeSessionSnapshot()
                        val replay = read.nativeRpc("session.events.since", json("session_id" to session, "last_seen" to 0, "replay_epoch" to snapshot.getString("replay_epoch")))
                        check(!replay.optBoolean("truncated"))
                        val events = replay.rows("events").filter { it.string("session_id") == session }
                        val starts = events.filter { it.string("type") == "message.start" }; check(starts.size == 1 && starts.single().string("admission_id") == job.string("admission_id"))
                        val inputs = events.flatMap { it.optJSONObject("payload")?.rows("pending").orEmpty() }.filter { it.string("input_id") == job.string("request_id") }
                        check(inputs.isNotEmpty() && inputs.map { it.string("admission_id") }.distinct() == listOf(job.string("admission_id")))
                        val completions = events.filter { it.string("type") == "message.complete" && it.string("admission_id") == job.string("admission_id") }
                        check(completions.size <= 1)
                        if (completions.isEmpty()) return@await false
                        val terminal = completions.single()
                        val proof = terminal.getJSONObject("payload").getJSONObject("persisted_turn")
                        val users = proof.getJSONArray("user_row_ids"); val user = proof.getLong("user_row_id"); val finalRow = proof.getLong("final_assistant_row_id")
                        check(proof.opt("complete") == true && users.length() == 1 && users.getLong(0) == user && user > 0 && finalRow > 0)
                        fun rowId(row: JSONObject) = row.optLong("row_id", row.optLong("_row_id", row.optLong("id")))
                        val rows = snapshot.rows("messages")
                        check(rows.count { it.string("role") == "user" && rowId(it) == user } == 1)
                        check(rows.single { it.string("role") == "assistant" && rowId(it) == finalRow }.string("content").contains(marker))
                        id("native_user_row_id", user.toString()); id("native_final_row_id", finalRow.toString())
                        evidence.put("native_submissions", starts.size).put("native_work_user_rows", users.length()).put("same_chat_final", true)
                    true
                    } finally { read.closeNative() }
                }
                save("same_chat_identity"); check(recipient(initialSource) == initialSource)
                var invitation: JSONObject? = null
                var noticeCursor = 0L
                await(60_000) {
                    delay(750)
                    val page = parseKeepConnectedPage(client.nativeRequest("/api/plugins/beacon/mobile/notifications/events", params = mapOf("after" to noticeCursor.toString(), "wait" to "0")), noticeCursor)
                    noticeCursor = page.nextCursor
                    val matches = page.events.filter { it.string("kind") == "incoming_call" && it.string("job_id") == job.string("job_id") }
                    check(matches.size <= 1)
                    invitation = matches.singleOrNull(); invitation != null
                }
                val notice = checkNotNull(invitation); val event = notice.getString("event_id"); id("qa_callback_event", event)
                val source = notice.getString("source_ref")
                check(source.isNotBlank() && (initialSource == null || source == initialSource) && notice.string("profile") == scope.profile && notice.string("owner_id") == scope.owner && notice.string("conversation_id") == session)
                requireIncomingRoute(notice, source, scope.profile); evidence.put("callback_invitation", true); save("callback_invitation")
                // The first notice also establishes the local source binding; the invitation store only
                // permits this source after that binding exists, so verify the recipient before waiting.
                check(recipient(source) == source)
                await(30_000) { app.push.invitation(source, event)?.valid() == true }
                check(app.push.authorizeKeepConnectedAction(source, event, "answer"))
                evidence.put("recipient_verified", true).put("recipient_source_pending", false).put("callback_action_receipt_verified", true); save("callback_arrived")
                withContext(Dispatchers.Main.immediate) { app.calls.answer(source, event) }
                await(45_000) {
                    app.calls.service?.mute(true)
                    app.calls.state.value.let { it.active && it.callId.isNotBlank() && it.callId != call && it.voiceProvider == "grok" && it.conversationScope == scope.session(session) && it.state in setOf("thinking", "listening", "speaking") } &&
                        app.calls.stageLog.any { it.string("stage") == "provider_ready" && it.string("provider") == "grok" }
                }
                callback = app.calls.state.value.callId; ownedCalls += callback; id("qa_callback_call_id", callback)
                val context = client.nativeCallContext(callback); val reference = context.getJSONObject("callback")
                check(context.string("profile") == scope.profile && context.string("selected_session_id") == session && reference.string("job_id") == job.string("job_id") && reference.string("result").contains(marker))
                evidence.put("callback_ready_same_job", true); save("callback_ready")
                await(45_000) {
                    app.calls.state.value.callId == callback && app.calls.state.value.transcripts.any { it.eventId.startsWith("$callback:assistant:") && it.role == "assistant" && it.final && !it.interrupted && it.text.isNotBlank() } &&
                        app.calls.stageLog.any { it.string("stage") == "playout_started" && it.string("provider") == "grok" }
                }
                check(callState(callback).rows("jobs").isEmpty())
                evidence.put("callback_greeting_final", true).put("callback_playout_started", true); save("callback_greeting")
                withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
                stopped(); check(callState(callback).opt("closed") == true && callState(callback).rows("jobs").isEmpty())
                evidence.put("callback_audio_ended", true); save("callback_ended")
                check(vm.authenticatedScope() == scope.session(session) && vm.composer.value.text.isBlank() && vm.chat.value.uncertain.isBlank())
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("message-input").fetchSemanticsNodes().size == 1 }
                val followup = "Reply with exactly BEACON_FOLLOWUP_" + runId.replace("-", "")
                val followupMarker = followup.removePrefix("Reply with exactly ")
                compose.onNodeWithTag("message-input").performTextInput(followup)
                compose.onNodeWithTag("message-send").performClick()
                evidence.put("composer_sends", 1); save("post_call_send")
                await(120_000) {
                    current(); check(vm.authenticatedScope() == scope.session(session))
                    vm.chat.value.messages.any { it.role == "assistant" && it.text.contains(followupMarker) } && vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank()
                }
                val user = vm.chat.value.messages.filter { it.role == "user" && it.text == followup }
                check(user.size == 1 && user.single().id.isNotBlank() && vm.composer.value.text.isBlank())
                id("followup_user_row_id", user.single().id)
                save("post_call_receipt_settling")
                await(90_000) { current(); vm.chat.value.queued.isEmpty() && vm.chat.value.uncertain.isBlank() }
                evidence.put("post_call_local_receipts_cleared", true)
                evidence.put("post_call_admitted", true).put("post_call_final", true).put("journey_passed", true)
                complete = true; save("post_call_complete")
            } finally {
                val state = app.calls.state.value
                if (state.active && state.conversationScope == scope.session(session) && (state.callId in ownedCalls || started && state.callId.isBlank())) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { app.calls.hangup() }
                }
                if (complete && session.isNotBlank() && runCatching { current() }.isSuccess) {
                    runCatching {
                        check(ownedCalls.all { callState(it).opt("closed") == true })
                        val deleted = client.detached().post("/api/session/delete", json("session_id" to session))
                        evidence.put("conversation_deleted", deleted.string("deleted") == session)
                    }
                }
                // Calls/jobs/answered invitations have no per-record public purge API; leave their exact QA IDs for Root.
                evidence.put("cleanup_pending", true).put("status", if (complete) "passed_with_cleanup_pending" else "failed_or_blocked")
                voice.setSelection(previousVoice)
                check(voice.settings() == previousVoice)
                save(evidence.string("phase"))
            }
        }
    }
}
