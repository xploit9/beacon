package dev.hermes.android.journey

import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.voice.interleaveCallMessages
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real disposable-demo regression; no mock client, fabricated receipt, speech fixture or replay. */
class MixedChatLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    private var verified: Scope? = null
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("beaconMixedChatLive") == "1")
            try {
                check(args.getString("beaconNoHostAudio") == "1")
                val profile = args.getString("beaconQaProfile").orEmpty()
                check(profile.matches(Regex("qa-[a-f0-9]{32}")))
                val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
                runBlocking {
                    val origin = trustedOrigin(app.preferences.data.first()[stringPreferencesKey("origin")].orEmpty())
                    check(Uri.parse(origin).scheme == "https" && Uri.parse(origin).port == 8446)
                    val expected = args.getString("beaconMixedChatOriginSha256").orEmpty()
                    check(expected.isEmpty() || expected.matches(Regex("[a-f0-9]{64}")) && origin.digest() == expected)
                    val auth = app.auth.read(origin)
                    val selected = checkNotNull(auth.cachedScopeFor(origin, replacingAccount = false))
                    check(selected.profile == profile && selected.origin == origin)
                    verified = selected
                    val read = BeaconClient(auth, app.auth, persistContext = false)
                    try {
                        val versions = read.nativeRequest("/api/plugins/beacon/mobile/plugin/version")
                        val version = BuildConfig.VERSION_NAME.removeSuffix("-preview")
                        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
                        check(listOf("running", "installed", "gateway").all { versions.string(it) == version })
                        check(installed.versionName == BuildConfig.VERSION_NAME && installed.longVersionCode == BuildConfig.VERSION_CODE.toLong())
                    } finally { read.closeNative() }
                }
                base.evaluate()
            } catch (skip: AssumptionViolatedException) { throw skip }
            catch (error: Throwable) {
                val detail = (error.message ?: "").replace(Regex("https?://\\S+"), "[url]").take(160)
                throw AssertionError("mixed_chat_live_guard_or_journey_failed: " + error.javaClass.simpleName +
                    (if (detail.isBlank()) "" else ": " + detail))
            }
        }
    } }
    // Both rules precede activity launch, including on ordinary non-opted-in instrumentation runs.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(optIn).around(compose)

    @Test(timeout = 480_000) fun completedGrokCallThenImageAndLunaReplyStayOrderedAcrossSync() = runBlocking {
        val h = OwnerJourneyHarness(compose, "mixed-chat-live")
        h.checked {
            qa(); check(scope == verified)
            check(vm.composer.value.text.isBlank() && vm.attachments.value.isEmpty() && vm.chat.value.queued.isEmpty())
            check(vm.chat.value.liveText.isBlank() && vm.chat.value.liveTools.isEmpty() && !vm.ui.value.botModeEnabled)
            val owner = scope
            val began = System.nanoTime()
            val evidence = json("run" to runId, "phase" to "preflight", "passed" to false, "cleanup_pending" to true,
                "no_host_audio_attested" to true, "raw_audio_retained" to false, "send_attempts" to 0)
            val output = AtomicFile(File(app.filesDir, "mixed-chat-live.json"))
            fun save(phase: String) {
                evidence.put("phase", phase)
                val stream = output.startWrite()
                try { stream.write(evidence.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
                catch (error: Throwable) { output.failWrite(stream); throw error }
            }
            fun id(key: String, value: String) {
                check(value.matches(Regex("[A-Za-z0-9_.:-]{1,256}")))
                evidence.put(key, value)
            }
            val voice = VoiceKeyStore(app)
            val previousVoice = voice.settings()
            val keptOn = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val local = AtomicReference<QueuedMessage?>()
            val active = AtomicBoolean(false)
            val reopening = AtomicBoolean(false)
            val sawBody = AtomicBoolean(false)
            val lostBody = AtomicBoolean(false)
            val seenRows = ConcurrentHashMap.newKeySet<String>()
            val visibility = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
            var baselineRows = emptySet<String>()
            var session = ""; var call = ""; var creationVerified = false; var callStarted = false
            var workspace = ""
            var creationAttempted = false
            var submitted = false; var reconciled = false; var complete = false
            var image: File? = null
            var observer: Job? = null; var muteGuard: Job? = null
            fun ownChat(): Boolean = session.isNotBlank() && vm.authenticatedScope() == owner.session(session)
            fun timeline(chat: ChatState): List<Message> = interleaveCallMessages(
                chatTimelineMessages(visibleTranscriptMessages(chat.messages), chat.queued),
                chat.callMessages.map { Message("call:${it.eventId}", it.role, it.text, recordedAt = it.at) })
            fun ordered(input: String, reply: String? = null) {
                val rows = timeline(vm.chat.value)
                val c = rows.indexOfFirst { it.id.startsWith("call:$call:") }
                val u = rows.indexOfFirst { it.id == input }
                check(c >= 0 && u > c) { "mixed_order_call_input" }
                if (reply != null) check(rows.indexOfFirst { it.id == reply } > u) { "mixed_order_input_reply" }
            }
            fun visibleBody(chat: ChatState): Boolean {
                val ownedRows = chat.messages.filter { it.role == "assistant" && it.id !in baselineRows && it.text.isNotBlank() }
                seenRows.addAll(ownedRows.map { it.id })
                val submission = local.get()?.submissionId.orEmpty()
                return chat.liveText.isNotBlank() || ownedRows.isNotEmpty() || chat.raw.rows("_unconfirmed_replies").any {
                    it.string("text").isNotBlank() && (submission.isNotBlank() && it.string("submission_id") == submission || it.string("_history_row_id") in seenRows)
                }
            }
            fun renderedReply() {
                val chat = vm.chat.value
                val row = chat.messages.lastOrNull { it.role == "assistant" && it.id !in baselineRows && it.text.isNotBlank() }
                val retained = chat.raw.rows("_unconfirmed_replies").lastOrNull {
                    it.string("text").isNotBlank() && (it.string("submission_id") == local.get()?.submissionId || it.string("_history_row_id") in seenRows)
                }
                val tag = when {
                    chat.liveText.isNotBlank() -> "live-reply"
                    row != null -> "message-row:${row.id}"
                    retained != null -> "unconfirmed-reply:${retained.string("id")}"
                    else -> error("owned_reply_not_renderable")
                }
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
            suspend fun nativeSnapshot(): JSONObject {
                current(); check(scope == owner)
                val read = client.detached()
                try {
                    val binding = read.nativeSession(session)
                    check(binding.scope == owner && binding.storedSessionId == session && binding.runtimeSessionId == session)
                    check(read.nativeTurnLease(session, session).authority)
                    return read.nativeSessionSnapshot().also {
                        val info = it.getJSONObject("info")
                        check(info.string("profile_name") == owner.profile && info.string("profile_id").isNotBlank())
                        check(info.string("model").substringAfterLast('/').substringBefore('[') == "gpt-6-luna")
                        check(workspace.isNotBlank() && info.string("cwd").replace('\\', '/').trimEnd('/') == workspace)
                    }
                } finally { read.closeNative() }
            }
            suspend fun receipt(proof: Boolean = false): JSONObject {
                current(); check(ownChat())
                val item = checkNotNull(local.get())
                check(item.submissionId.isNotBlank() && item.admissionId.isNotBlank())
                val read = client.detached()
                try { return read.get("/api/mobile/receipts/${item.submissionId}", mapOf("session_id" to session,
                    "admission_id" to item.admissionId, "completed_proof" to if (proof) "1" else "0")) }
                finally { read.closeNative() }
            }
            fun idle(snapshot: JSONObject): Boolean = snapshot.opt("running") == false && snapshot.opt("pending") is JSONArray &&
                snapshot.rows("pending").isEmpty() && snapshot.opt("prompts") is JSONArray && snapshot.rows("prompts").isEmpty() &&
                snapshot.opt("messages") is JSONArray && snapshot.opt("message_count") is Number &&
                snapshot.optLong("message_count") == snapshot.getJSONArray("messages").length().toLong()
            suspend fun reopen(reconnect: Boolean) {
                current()
                reopening.set(true)
                try {
                    if (reconnect) {
                        withContext(Dispatchers.Main.immediate) { vm.connect(owner.origin) }.join()
                        connect(); check(scope == owner)
                    }
                    withContext(Dispatchers.Main.immediate) { vm.selectSession(session); vm.awaitSelection() }
                    await(45_000) { ownChat() && vm.chat.value.projectionReady && vm.ui.value.connected }
                    current()
                } finally {
                    reopening.set(false)
                }
            }
            try {
                check(voice.hasKey("grok"))
                voice.setSelection(voice.settings("grok"))
                withContext(Dispatchers.Main.immediate) { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); vm.tab("Chat") }
                val previousSession = vm.chat.value.sessionId
                val body = liveTestChatBody(client, "Mixed chat live $runId")
                workspace = body.getString("workspace").replace('\\', '/').trimEnd('/')
                creationAttempted = true
                withContext(Dispatchers.Main.immediate) { vm.newChat(body) }.join()
                withContext(Dispatchers.Main.immediate) { vm.awaitSelection() }
                await(60_000) { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening }
                session = vm.chat.value.sessionId
                check(ownChat() && app.cache.read(owner.session(session), "client-created-shell") != null)
                creationVerified = true; id("session_id", session)
                val draft = nativeSnapshot()
                check(idle(draft) && draft.rows("messages").none { it.string("role") == "user" })
                save("call_start")
                muteGuard = launch(Dispatchers.Main.immediate) { app.calls.state.collect { state ->
                    if (callStarted && state.active && state.conversationScope == owner.session(session) && !state.muted) app.calls.service?.mute(true)
                } }
                callStarted = true
                withContext(Dispatchers.Main.immediate) { app.calls.start(client, session) }
                await(45_000) {
                    app.calls.service?.mute(true)
                    app.calls.state.value.let { it.active && it.callId.isNotBlank() && it.voiceProvider == "grok" && it.conversationScope == owner.session(session) && it.muted && it.state in setOf("listening", "thinking", "speaking") }
                }
                call = app.calls.state.value.callId; id("call_id", call)
                val context = client.nativeCallContext(call)
                check(context.string("profile") == owner.profile && context.string("selected_session_id") == session && context.string("backend") in setOf("hermes", "direct"))
                await(45_000) {
                    val greetingDone = runCatching {
                        val service = checkNotNull(app.calls.service)
                        val direct = service.javaClass.getDeclaredField("directVoice").apply { isAccessible = true }.get(service)
                        val lock = direct.javaClass.getDeclaredField("greetingLock").apply { isAccessible = true }.get(direct)
                        synchronized(lock) { direct.javaClass.getDeclaredField("greetingComplete").apply { isAccessible = true }.getBoolean(direct) }
                    }.getOrDefault(false)
                    greetingDone && app.calls.state.value.transcripts.any { it.role == "assistant" && it.final && !it.interrupted } && !app.calls.state.value.remoteSpeaking
                }
                val earlier = app.calls.state.value.transcripts.map { it.eventId }.toSet()
                check(app.calls.sendText(call, "Give one short fictional greeting. Do not use tools, start work, schedule a callback or contact anyone."))
                await(45_000) { app.calls.state.value.transcripts.any { it.eventId !in earlier && it.role == "assistant" && it.final && !it.interrupted && it.text.isNotBlank() } && !app.calls.state.value.remoteSpeaking }
                withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
                await(45_000) { !app.calls.state.value.active && app.calls.service == null }
                val closed = client.nativeRequest("/api/plugins/beacon/mobile/calls/$call")
                check(closed.opt("closed") == true && closed.string("transcript_status") == "saved" && closed.opt("audio_available") == false)
                reopen(false)
                await(30_000) { vm.chat.value.callMessages.any { it.eventId.startsWith("$call:") && it.final && it.at > 0 } }
                baselineRows = vm.chat.value.messages.map { it.id }.toSet()
                val beforeSend = nativeSnapshot()
                val startsBefore = beforeSend.optLong("last_sequence")
                val eventEpoch = beforeSend.string("replay_epoch").also { check(it.isNotBlank()) }
                evidence.put("replay_epoch_sha256", eventEpoch.digest())
                evidence.put("call_completed", true).put("call_muted", true).put("qa_workspace_verified", true).put("luna_model_verified", true); save("call_complete")

                // The only local file read is this freshly generated private image, removed immediately after staging.
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                val png = ByteArrayOutputStream().use { bytes -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bytes.toByteArray() }
                bitmap.recycle()
                image = File.createTempFile("mixed-${runId.take(8)}-", ".png", File(app.cacheDir, "exports").apply { mkdirs() })
                image!!.writeBytes(png); png.fill(0)
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", image!!)
                withContext(Dispatchers.Main.immediate) { vm.upload(uri) }.join()
                check(vm.attachments.value.size == 1 && vm.attachments.value.single().string("scope_key") == owner.session(session).key)
                check(image!!.delete()); image = null
                observer = launch(Dispatchers.Main.immediate) { vm.chat.collect { chat ->
                    if (chat.sessionId == session) {
                        chat.queued.singleOrNull()?.takeIf { it.submissionId.isNotBlank() && it.admissionId.isNotBlank() }?.let { local.compareAndSet(null, it) }
                        if (visibility.size < 48) {
                            val ownedNow = chat.messages.count { it.role == "assistant" && it.id !in baselineRows && it.text.isNotBlank() }
                            val unconfirmedNow = chat.raw.rows("_unconfirmed_replies").count { it.string("text").isNotBlank() }
                            visibility.add(json("at" to (System.nanoTime() - began) / 1_000_000, "active" to active.get(),
                                "reopening" to reopening.get(), "projection" to chat.projectionReady,
                                "loading" to vm.ui.value.loading, "restoring" to vm.ui.value.restoringSession,
                                "messages" to chat.messages.size, "owned_rows" to ownedNow,
                                "live_len" to chat.liveText.length, "unconfirmed" to unconfirmedNow,
                                "stream" to chat.streamId.isNotBlank(),
                                "body" to (chat.liveText.isNotBlank() || ownedNow > 0 || unconfirmedNow > 0)))
                        }
                        if (active.get() && !reopening.get() && chat.projectionReady && !vm.ui.value.loading && !vm.ui.value.restoringSession) {
                            if (visibleBody(chat)) sawBody.set(true) else if (sawBody.get()) lostBody.set(true)
                        }
                    }
                } }
                withContext(Dispatchers.Main.immediate) {
                    vm.composer(androidx.compose.ui.text.input.TextFieldValue("Inspect this synthetic image. First give a brief visible progress sentence. Then run exactly one harmless terminal command, python -c \"import time; time.sleep(20)\", only in this chat's configured QA workspace. Do not read or modify any files, delegate, schedule callbacks, contact anyone, make purchases or retry work. Finally give one short fictional completion sentence."))
                    submitted = true; evidence.put("send_attempts", 1); vm.send()
                }
                await(45_000) { local.get() != null }
                val item = checkNotNull(local.get()); id("submission_id", item.submissionId); id("admission_id", item.admissionId)
                await(60_000) { delay(750); receipt().string("status") in setOf("started", "terminal") && visibleBody(vm.chat.value) }
                active.set(true); sawBody.set(true)
                check(vm.chat.value.approval == null && vm.chat.value.clarification == null)
                val displayedInput = vm.chat.value.queued.singleOrNull { it.id == item.id }?.rowId?.takeIf { id ->
                    id.isNotBlank() && vm.chat.value.messages.any { it.id == id && it.role == "user" }
                } ?: "queued:${item.id}"
                ordered(displayedInput); renderedReply(); evidence.put("active_reply_visible", true); save("working")
                reopen(false)
                check(receipt().string("status") in setOf("started", "terminal") && visibleBody(vm.chat.value))
                renderedReply()
                reopen(true)
                check(receipt().string("status") in setOf("started", "terminal") && visibleBody(vm.chat.value))
                renderedReply()
                check(!lostBody.get()); evidence.put("active_reselect_reconnect", true); save("working_reconnected")

                var proven: JSONObject? = null
                await(150_000) { delay(750); val value = receipt(true)
                    if (value.string("status") == "terminal" && value.string("outcome") == "completed" && value.optJSONObject("_confirmed_persisted_turn") != null) proven = value
                    proven != null
                }
                active.set(false); check(!lostBody.get())
                val proof = checkNotNull(proven).getJSONObject("_confirmed_persisted_turn")
                check(proof.opt("complete") == true && proof.string("submission_id") == item.submissionId)
                val user = proof.getLong("user_row_id").also { check(it > 0) }.toString()
                val final = proof.getLong("final_assistant_row_id").also { check(it > 0) }.toString()
                check(proof.getJSONArray("user_row_ids").length() == 1 && proof.getJSONArray("user_row_ids").getLong(0).toString() == user)
                val proofRows = proof.getJSONArray("row_ids").let { rows -> (0 until rows.length()).map { rows.getLong(it).also { id -> check(id > 0) }.toString() } }
                check(user != final && user in proofRows && final in proofRows && proofRows.distinct().size == proofRows.size)
                evidence.put("terminal_proof", true).put("native_user_row_id", user).put("native_final_row_id", final); save("terminal_proof")
                try {
                    await(90_000) { vm.chat.value.messages.any { it.id == user && it.role == "user" && it.attachments.isNotEmpty() } &&
                        vm.chat.value.messages.any { it.id == final && it.role == "assistant" && it.text.isNotBlank() } &&
                        vm.chat.value.queued.none { it.id == item.id } && vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isBlank() }
                } catch (error: TimeoutCancellationException) {
                    val chat = vm.chat.value
                    val cached = runCatching { app.cache.read(owner, "attachments:$user") }.getOrNull()
                    evidence.put("row_wait_messages", chat.messages.size)
                        .put("row_wait_has_user", chat.messages.any { it.id == user })
                        .put("row_wait_has_final", chat.messages.any { it.id == final })
                        .put("row_wait_user_attachments", chat.messages.firstOrNull { it.id == user }?.attachments?.size)
                        .put("row_wait_cache_present", cached != null)
                        .put("row_wait_cache_attachments", runCatching { cached?.let { JSONObject(it).rows("attachments").size } }.getOrNull())
                        .put("row_wait_queued", chat.queued.size)
                        .put("row_wait_uncertain", chat.uncertain.isNotBlank())
                        .put("row_wait_stream", chat.streamId.isNotBlank())
                        .put("row_wait_projection", chat.projectionReady)
                    save("row_wait_failed")
                    throw error
                }
                reconciled = true; ordered(user, final)
                id("user_row_id", user); id("final_row_id", final)
                val digest = vm.chat.value.messages.single { it.id == final && it.role == "assistant" }.text.digest()
                evidence.put("final_sha256", digest).put("input_reconciled", true)
                val read = client.detached()
                try {
                    read.nativeSession(session)
                    check(read.nativeSessionSnapshot().string("replay_epoch") == eventEpoch)
                    val replay = read.nativeRpc("session.events.since", json("session_id" to session, "last_seen" to startsBefore,
                        "replay_epoch" to eventEpoch))
                    check(replay.opt("truncated") == false && replay.opt("snapshot_required") != true)
                    val starts = replay.rows("events").filter { it.string("type") == "message.start" }
                    check(starts.size == 1 && starts.single().string("admission_id") == item.admissionId)
                    evidence.put("native_start_count", starts.size)
                } finally { read.closeNative() }
                for (reconnect in listOf(false, true)) {
                    reopen(reconnect); ordered(user, final)
                    check(vm.chat.value.messages.singleOrNull { it.id == final && it.role == "assistant" }?.text?.digest() == digest)
                    check(vm.chat.value.messages.singleOrNull { it.id == user && it.role == "user" }?.attachments?.isNotEmpty() == true)
                }
                compose.activityRule.scenario.recreate(); connect(); check(scope == owner)
                reopen(false); ordered(user, final)
                check(vm.chat.value.messages.singleOrNull { it.id == final && it.role == "assistant" }?.text?.digest() == digest)
                compose.onNodeWithTag("message-row:$final").performScrollTo().assertExists()
                evidence.put("reopen_identity_and_text", true); complete = true; save("complete")
            } finally { withContext(NonCancellable) { try {
                evidence.put("send_outcome_unconfirmed", submitted && !reconciled).put("replayed", false)
                    .put("create_outcome_unconfirmed", creationAttempted && !creationVerified)
                evidence.put("visibility_transitions", JSONArray(visibility.toList()))
                observer?.cancelAndJoin(); muteGuard?.cancelAndJoin()
                val state = app.calls.state.value
                if (callStarted && state.active && state.conversationScope == owner.session(session) && (call.isBlank() || state.callId == call)) {
                    withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
                    withTimeoutOrNull(45_000) { while (app.calls.service != null) delay(100) }
                }
                image?.let { check(it.canonicalFile.parentFile == File(app.cacheDir, "exports").canonicalFile); check(!it.exists() || it.delete()) }
                if (complete && reconciled && creationVerified && ownChat() && runCatching { current() }.isSuccess) {
                    check(idle(nativeSnapshot()))
                    check(client.nativeRequest("/api/plugins/beacon/mobile/calls/$call").opt("closed") == true)
                    val deleted = client.detached()
                    try { check(deleted.post("/api/session/delete", json("session_id" to session, "scope_key" to owner.session(session).key)).string("deleted") == session) }
                    finally { deleted.closeNative() }
                    evidence.put("conversation_deleted", true).put("cleanup_pending", false)
                }
                if (complete) check(evidence.opt("conversation_deleted") == true)
                } finally {
                    try { voice.setSelection(previousVoice); check(voice.settings() == previousVoice) }
                    finally {
                        withContext(Dispatchers.Main.immediate) {
                            if (keptOn) compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        evidence.put("voice_restored", voice.settings() == previousVoice).put("screen_flag_restored", true)
                            .put("elapsed_ms", (System.nanoTime() - began) / 1_000_000)
                            .put("passed", complete && evidence.opt("conversation_deleted") == true && voice.settings() == previousVoice)
                        save(if (evidence.optBoolean("passed")) "finished" else "failed_no_replay")
                    }
                }
            } }
        }
    }
}
