package dev.hermes.android

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in demo-authority journey using the app's own client and ViewModel. */
class BusyTurnSendLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()

    @Test(timeout = 420_000) fun queuedFollowUpUsesTheActualNativeConversation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconBusySendLive") == "1")
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(BuildConfig.DEBUG && app.packageName.endsWith(".debug"))
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.FINGERPRINT.contains("emulator") || android.os.Build.MODEL.contains("sdk"))
        require(!app.calls.state.value.active && app.calls.service == null)
        val diagnosticsFile = File(app.filesDir, "busy-send-live-phase.json")
        if (diagnosticsFile.exists()) check(diagnosticsFile.delete()) { "Could not replace private phase metadata" }
        val prior = app.preferences.data.first()
        require(prior[stringPreferencesKey("origin")].isNullOrBlank()) { "Use an isolated debug install without a saved connection" }
        val authFile = File(app.filesDir, "live-auth.json")
        val credentials = try { JSONObject(authFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")) } finally { if (authFile.exists()) check(authFile.delete()) }
        require(credentials.optString("purpose") == "beacon_busy_send_demo") { "A dedicated demo fixture is required" }
        val origin = trustedOrigin(credentials.getString("origin"))
        val existingAuth = app.auth.read(origin)
        require(existingAuth.owner.isBlank() && existingAuth.cookies.isEmpty() && existingAuth.bearer == null && existingAuth.loopbackSession == null) { "Use a debug install without saved credentials" }
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms: Long = 90_000, pollMs: Long = 100, ready: suspend () -> Boolean) = withTimeout(ms) { while (!ready()) delay(pollMs) }
        var createdScope: Scope? = null
        var createdSessionHash: String? = null
        var runtime = ""
        val title = "Beacon busy-send QA ${UUID.randomUUID()}"
        val streamSeen = AtomicBoolean(false)
        var streamObserver: Job? = null
        var previousSession: String? = null
        var phase = "setup"
        var failure: JSONObject? = null
        var cleanupFailure: JSONObject? = null
        var diagnosticFailure: Exception? = null
        var secondReplyVerified = false
        var authoritativeIdleVerified = false
        var rowIdentityPresent = false
        var admissionIdentityPresent = false
        var deletionCheck = "not_run"
        val knownStates = setOf("queued", "sending", "accepted", "unknown", "sent", "settled")
        fun errorCategory(text: String): String = when (text) {
            "" -> "none"
            "Stop the current reply before deleting this conversation.", "This chat is busy with a reply. Try again when it finishes." -> "busy_reply"
            "Conversation close was not confirmed." -> "close_unconfirmed"
            "Deletion was not confirmed. Refresh before trying again." -> "delete_unconfirmed"
            "Refresh this conversation to confirm whether a reply is still running." -> "idle_unconfirmed"
            "Can't reach Hermes. Check your connection, then try again." -> "connection"
            "Sign-in expired. Sign in again.", "Sign in with Hermes again, then try again." -> "authentication"
            "Hermes couldn't do that. Try again." -> "operation_unclassified"
            else -> "other"
        }
        fun snapshot(): JSONObject {
            val ui = vm.ui.value
            val chat = vm.chat.value
            return json("connected" to ui.connected, "authenticated" to ui.authenticated, "client_present" to (vm.client != null),
                "projection_ready" to chat.projectionReady, "conversation_selected" to chat.sessionId.isNotBlank(),
                "new_conversation_selected" to (previousSession != null && chat.sessionId.isNotBlank() && chat.sessionId != previousSession),
                "qa_title_matches" to (chat.title == title), "new_chat_opening" to ui.newChatOpening, "new_chat_error_present" to ui.newChatError.isNotBlank(), "restoring_session" to ui.restoringSession,
                "stream_active" to chat.streamId.isNotBlank(), "stream_seen" to streamSeen.get(), "live_output_present" to chat.liveText.isNotBlank(),
                "user_message_present" to chat.messages.any { it.role == "user" }, "assistant_message_present" to chat.messages.any { it.role == "assistant" },
                "queued_count" to chat.queued.size, "queued_states" to JSONArray(chat.queued.take(16).map { if (it.state in knownStates) it.state else "other" }),
                "error_present" to ui.error.isNotBlank(), "ui_error_category" to errorCategory(ui.error), "notice_present" to ui.notice.isNotBlank(),
                "created_scope_proven" to (createdScope != null), "same_created_scope" to (createdScope != null && vm.authenticatedScope() == createdScope && chat.sessionId == createdScope?.session),
                "row_identity_present" to rowIdentityPresent, "admission_identity_present" to admissionIdentityPresent,
                "second_reply_verified" to secondReplyVerified, "authoritative_idle_verified" to authoritativeIdleVerified, "deletion_check" to deletionCheck).apply {
                    createdSessionHash?.let { put("created_session_sha256", it) }
                }
        }
        fun writeDiagnostics() {
            try {
                val output = snapshot().put("phase", phase)
                    .put("failed_phase", failure?.optString("phase").orEmpty())
                    .put("exception_class", failure?.optString("exception_class").orEmpty())
                failure?.let { output.put("failure", it) }
                cleanupFailure?.let { output.put("cleanup_failure", it) }
                diagnosticsFile.writeText(output.toString(), Charsets.UTF_8)
                diagnosticFailure = null
            } catch (error: Exception) { diagnosticFailure = error }
        }
        fun mark(next: String) { phase = next; writeDiagnostics() }
        fun recordFailure(error: Throwable) {
            val recorded = snapshot().put("phase", phase).put("exception_class", error.javaClass.simpleName.take(100))
            if (failure == null) failure = recorded else if (cleanupFailure == null) cleanupFailure = recorded
            writeDiagnostics()
        }
        fun scopedClient(scope: Scope): dev.hermes.android.data.BeaconClient? {
            val active = vm.client ?: return null
            check(vm.authenticatedScope() == scope && vm.chat.value.sessionId == scope.session && active.scope == scope.copy(session = "")) { "The QA conversation changed" }
            return active
        }
        suspend fun historyHasReply(scope: Scope, userRowId: String): Boolean {
            val active = scopedClient(scope) ?: return false
            val reader = active.detached()
            try {
                val projection = reader.get("/api/session", mapOf("session_id" to scope.session))
                check(vm.client === active && vm.authenticatedScope() == scope && reader.scope == scope.copy(session = "") &&
                    projection.string("session_id") == scope.session && projection.string("profile", scope.profile) == scope.profile) { "The QA history scope changed" }
                val rows = (projection.optJSONObject("session") ?: projection).rows("messages")
                val user = rows.indexOfFirst { it.string("role") == "user" && it.string("row_id", it.string("id")) == userRowId }
                if (user < 0) return false
                val answer = rows.drop(user + 1).takeWhile { it.string("role") != "user" }.lastOrNull { row ->
                    row.string("role") == "assistant" && (row.string("row_id", row.string("id")).toLongOrNull() ?: 0L) > 0
                } ?: return false
                return Message.from(answer, 0, scope.session).text.isNotBlank()
            } catch (error: CancellationException) { throw error }
            catch (error: ApiFailure) { if (error.status in setOf(401, 403)) throw error; return false }
            catch (_: IOException) { return false }
            finally { reader.closeNative() }
        }
        suspend fun nativeIdle(scope: Scope): Boolean {
            if (vm.chat.value.streamId.isNotBlank()) return false
            val active = scopedClient(scope) ?: return false
            val probe = active.detached()
            try {
                val rows = probe.nativeRpc("session.active_list").rows("sessions")
                val row = rows.singleOrNull { it.string("session_key") == scope.session } ?: return false
                val runtimeId = row.string("id")
                val idle = probe.nativeSessionStatus(scope.session, runtimeId) == "idle"
                return idle && vm.client === active && vm.authenticatedScope() == scope && vm.chat.value.streamId.isBlank()
            } catch (error: CancellationException) { throw error }
            catch (error: ApiFailure) { if (error.status in setOf(401, 403)) throw error; return false }
            catch (_: IOException) { return false }
            finally { probe.closeNative() }
        }
        suspend fun canonicalReply(scope: Scope, admissionId: String): Boolean {
            if (!nativeIdle(scope)) return false
            val active = scopedClient(scope) ?: return false
            val probe = active.detached()
            try {
                val binding = probe.nativeSession(scope.session)
                check(binding.scope == scope.copy(session = "") && binding.storedSessionId == scope.session) { "The QA admission scope changed" }
                val lease = probe.nativeTurnLease(scope.session, binding.runtimeSessionId)
                if (!lease.authority) return false
                val receipt = probe.nativeTurnRpc(lease, "prompt.receipt", json("session_id" to binding.runtimeSessionId, "admission_id" to admissionId, "include_result" to true))
                check(receipt.string("admission_id") == admissionId && receipt.optJSONObject("ref")?.string("session_id") == scope.session &&
                    vm.client === active && vm.authenticatedScope() == scope) { "The QA admission identity changed" }
                if (receipt.string("status") != "terminal") return false
                check(receipt.string("outcome") == "completed") { "The QA admission did not complete" }
                return receipt.optJSONObject("result")?.string("final_response")?.isNotBlank() == true
            } catch (error: CancellationException) { throw error }
            catch (error: ApiFailure) { if (error.status in setOf(401, 403)) throw error; return false }
            catch (_: IOException) { return false }
            finally { probe.closeNative() }
        }
        try {
            mark("login")
            loginLiveTest(app, origin, credentials)
            mark("connect")
            withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
            mark("await_connected")
            await { vm.ui.value.connected && vm.client != null }
            val previous = vm.chat.value.sessionId
            previousSession = previous
            mark("create")
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to title)) }.join()
            mark("await_created")
            await { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.title == title && vm.chat.value.projectionReady }
            mark("verify_created_scope")
            val scope = requireNotNull(vm.authenticatedScope())
            require(scope.session == vm.chat.value.sessionId && scope.session != previous)
            require(app.cache.read(scope, "client-created-shell") != null) { "The QA chat was not proven to be newly created" }
            createdScope = scope
            createdSessionHash = scope.session.digest()
            mark("created_scope_verified")
            streamObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.chat.collect { if (it.sessionId == scope.session && it.streamId.isNotBlank()) streamSeen.set(true) }
            }
            mark("set_queue_preference")
            withContext(Dispatchers.Main.immediate) { vm.setBusySendAction("queue") }.join()
            mark("first_send")
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("Explain why 17 is prime in a few short steps. Do not use tools or change anything.")); vm.send() }.join()
            mark("await_first_stream")
            await { vm.chat.value.streamId.isNotBlank() }
            runtime = vm.chat.value.streamId
            mark("queue_submit")
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("After that, state the next prime number. Do not use tools.")); vm.queue() }.join()
            mark("await_queue_bubble")
            await { vm.chat.value.queued.any { it.text.startsWith("After that,") } }
            val id = vm.chat.value.queued.single { it.text.startsWith("After that,") }.id
            mark("await_queue_outcome")
            await(120_000) { vm.chat.value.queued.firstOrNull { it.id == id }?.state in setOf("sent", "accepted", "unknown", "settled") }
            val result = vm.chat.value.queued.single { it.id == id }
            assertTrue("The queued message needs an actual acknowledgement", result.state in setOf("sent", "accepted") || result.state == "settled" && result.outcome == "completed")
            assertTrue("The same scoped conversation remains open", vm.authenticatedScope() == scope)
            val rowId = result.rowId.takeIf { (it.toLongOrNull() ?: 0L) > 0 }.orEmpty()
            val admissionId = result.admissionId
            rowIdentityPresent = rowId.isNotBlank(); admissionIdentityPresent = admissionId.isNotBlank()
            require(rowIdentityPresent || admissionIdentityPresent) { "The queued send has no authoritative identity" }
            mark("await_second_reply")
            if (rowIdentityPresent) await(120_000, 500) { historyHasReply(scope, rowId) }
            else await(120_000, 500) { canonicalReply(scope, admissionId) }
            secondReplyVerified = true
            mark("await_authoritative_idle")
            await(30_000, 500) { nativeIdle(scope) }
            authoritativeIdleVerified = true
            mark("journey_complete")
        } catch (error: Throwable) {
            recordFailure(error)
            throw error
        } finally {
            try {
                mark("cleanup")
                val scope = createdScope
                if (scope != null) {
                    require(vm.authenticatedScope() == scope && vm.chat.value.sessionId == scope.session) { "The QA conversation changed; it was not deleted" }
                    mark("cleanup_stop")
                    if (vm.chat.value.streamId.isNotBlank()) withContext(Dispatchers.Main.immediate) { vm.cancel() }.join()
                    mark("cleanup_await_idle")
                    withTimeoutOrNull(20_000) { while (vm.chat.value.streamId.isNotBlank()) delay(100) }
                    check(vm.chat.value.streamId.isBlank()) { "The QA conversation is still running; it was not deleted" }
                    mark("cleanup_verify_identity")
                    val current = requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session))
                    require(current.string("session_id") == scope.session && current.string("title") == title) { "The QA conversation identity changed; it was not deleted" }
                    mark("cleanup_delete")
                    withContext(Dispatchers.Main.immediate) { vm.action("/api/session/delete", json("session_id" to scope.session), refresh = false, successNotice = "") }.join()
                    mark("cleanup_confirm_deleted")
                    val failure = runCatching { requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session)) }.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                    deletionCheck = when { failure == null -> "still_present"; failure is ApiFailure && failure.status == 404 -> "not_found"; failure is ApiFailure && failure.status in setOf(401, 403) -> "authentication"; else -> "unconfirmed" }
                    writeDiagnostics()
                    check(failure is ApiFailure && failure.status == 404) { "The QA conversation was not confirmed deleted" }
                    mark("cleanup_local_cache")
                    (listOf("draft", "pending", "session", "running-turn") + listOfNotNull(runtime.takeIf(String::isNotBlank)?.let { "live:$it" })).forEach { app.cache.delete(scope, it) }
                }
            } catch (error: Throwable) {
                recordFailure(error)
                throw error
            } finally {
                try {
                    try {
                        mark("cleanup_auth")
                        app.auth.remove(origin)
                    } finally {
                        mark("cleanup_preferences")
                        app.preferences.updateData { prior }
                    }
                    mark("finished")
                } catch (error: Throwable) {
                    recordFailure(error)
                    throw error
                } finally { streamObserver?.cancel() }
            }
        }
        check(diagnosticFailure == null) { "Could not write private phase metadata" }
    }
}
