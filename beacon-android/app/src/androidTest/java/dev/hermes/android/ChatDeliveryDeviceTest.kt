package dev.hermes.android

import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Parent-only live QA: explicit opt-in, expected QA profile, synthetic text, and no transcript output. */
class ChatDeliveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun completedInputsInSelectedQaChatReconcileFromNativeReceipts() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the coordinator's explicit QA opt-in", args.getString("beaconChatDeliveryLive") == "1")
        val profile = requireNotNull(args.getString("beaconChatDeliveryProfile"))
        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            var model: BeaconViewModel? = null
            val field = BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
            compose.waitUntil(30_000) {
                scenario.onActivity { model = field.get(it) as? BeaconViewModel }
                model?.let { it.client != null && it.ui.value.authenticated && it.ui.value.connected && !it.ui.value.loading && !it.ui.value.restoringSession } == true
            }
            val vm = checkNotNull(model)
            withContext(Dispatchers.Main.immediate) { vm.awaitSelection() }
            assertEquals(profile, vm.ui.value.profile)
            val sid = vm.chat.value.sessionId
            require(sid.isNotBlank() && vm.chat.value.projectionReady && vm.authenticatedScope()?.session == sid)
            val initial = vm.chat.value.queued.toList()
            val evidence = JSONObject().put("run", UUID.randomUUID().toString()).put("session_id", sid)
                .put("profile_matches", true).put("draft_empty", vm.composer.value.text.isBlank())
                .put("attachments", vm.attachments.value.size).put("stream_present", vm.chat.value.streamId.isNotBlank())
                .put("uncertain_present", vm.chat.value.uncertain.isNotBlank()).put("passed", false)
            val rows = org.json.JSONArray()
            evidence.put("receipts", rows)
            val output = AtomicFile(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "completed-input-live.json"))
            fun save() {
                evidence.put("remaining_count", vm.chat.value.queued.size).put("same_session", vm.chat.value.sessionId == sid)
                val stream = output.startWrite()
                try { stream.write(evidence.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
                catch (error: Throwable) { output.failWrite(stream); throw error }
            }
            save()
            try {
                for (item in initial.take(4)) {
                    val row = JSONObject().put("state", item.state).put("outcome", item.outcome).put("kind", item.kind)
                        .put("row_id_present", item.rowId.isNotBlank()).put("admission_id_present", item.admissionId.isNotBlank())
                        .put("submission_id_present", item.submissionId.isNotBlank())
                    rows.put(row)
                    if (item.submissionId.isNotBlank()) {
                        val c = checkNotNull(vm.client).detached()
                        try {
                            val receipt = c.get("/api/mobile/receipts/${item.submissionId}", mapOf("session_id" to sid, "admission_id" to item.admissionId, "completed_proof" to "1"))
                            val proof = receipt.optJSONObject("_confirmed_persisted_turn")
                            row.put("receipt_status", receipt.optString("status")).put("receipt_outcome", receipt.optString("outcome"))
                                .put("proof_present", proof != null).put("receipt_fields", org.json.JSONArray(receipt.keys().asSequence().toList()))
                            if (proof != null) {
                                val response = c.get("/api/session", mapOf("session_id" to sid))
                                val fresh = response.optJSONObject("session") ?: response
                                row.put("fresh_session_matches", fresh.optString("session_id") == sid).put("fresh_profile_matches", fresh.optString("profile") == profile)
                                fun history(value: JSONObject): List<JSONObject> = value.optJSONArray("messages")?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }.orEmpty()
                                row.put("displayed_rows", history(vm.chat.value.raw).size).put("fresh_rows", history(fresh).size)
                                row.put("retirement_matches", completedQueuedInputRow(item, receipt, history(vm.chat.value.raw), history(fresh), initial) != null)
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            row.put("error_class", error.javaClass.simpleName)
                        } finally { c.closeNative() }
                    }
                    save()
                }
                compose.waitUntil(90_000) { vm.chat.value.queued.isEmpty() }
                assertEquals(sid, vm.chat.value.sessionId)
                evidence.put("passed", true)
            } finally { save() }
        }
    }

    @Test fun ordinaryAndLongQueuedRepliesReachTheOpenChat() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the coordinator's explicit QA opt-in", args.getString("beaconChatDeliveryLive") == "1")
        val profile = requireNotNull(args.getString("beaconChatDeliveryProfile"))
        require(profile.isNotBlank())
        val mode = args.getString("beaconChatDeliveryMode", "fresh")
        require(mode in setOf("fresh", "notification", "post_voice"))
        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            var viewModel: BeaconViewModel? = null
            val field = BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
            compose.waitUntil(30_000) {
                scenario.onActivity { viewModel = field.get(it) as? BeaconViewModel }
                viewModel?.let { it.client != null && it.ui.value.let { ui -> ui.authenticated && ui.connected && !ui.loading && !ui.restoringSession } } == true
            }
            val vm = requireNotNull(viewModel)
            suspend fun awaitSelection() {
                withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { vm.awaitSelection() } }
            }
            awaitSelection()
            compose.waitUntil(30_000) { !vm.ui.value.newChatOpening && (vm.chat.value.sessionId.isBlank() || vm.chat.value.projectionReady) }
            assertEquals("Select the isolated QA profile before opting in", profile, vm.ui.value.profile)
            compose.waitUntil(90_000) { vm.chat.value.queued.isEmpty() || vm.chat.value.queued.any { it.state != "settled" || it.outcome != "completed" } }
            assertTrue("Keep existing work and drafts intact", vm.composer.value.text.isBlank() && vm.attachments.value.isEmpty() &&
                vm.chat.value.queued.isEmpty() && vm.chat.value.uncertain.isBlank() && vm.chat.value.streamId.isBlank())
            var opening: Job? = null
            compose.runOnUiThread {
                when (mode) {
                    "notification" -> opening = vm.handlePush(requireNotNull(args.getString("beaconChatDeliverySource")), requireNotNull(args.getString("beaconChatDeliveryEvent")))
                    "post_voice" -> vm.selectSession(requireNotNull(args.getString("beaconChatDeliverySession")))
                    else -> opening = vm.newChat()
                }
            }
            withTimeout(30_000) { opening?.join() }
            awaitSelection()
            compose.waitUntil(30_000) { opening?.isCompleted != false && vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && !vm.ui.value.newChatOpening &&
                vm.client != null && vm.ui.value.connected && !vm.ui.value.restoringSession && vm.authenticatedScope()?.session == vm.chat.value.sessionId &&
                (mode == "fresh" || vm.chat.value.sessionId == args.getString("beaconChatDeliverySession")) }
            assertTrue("The QA conversation opened successfully", vm.ui.value.newChatError.isBlank())
            if (mode != "fresh") assertEquals("Open only the requested synthetic QA conversation", args.getString("beaconChatDeliverySession"), vm.chat.value.sessionId)
            assertEquals(profile, vm.ui.value.profile)
            val session = withTimeout(30_000) { withContext(Dispatchers.Main.immediate) {
                    vm.awaitSelection()
                    check(vm.client != null && vm.ui.value.authenticated && vm.ui.value.connected && !vm.ui.value.loading && !vm.ui.value.restoringSession && !vm.ui.value.newChatOpening)
                    check(vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.authenticatedScope()?.session == vm.chat.value.sessionId && vm.authenticatedScope()?.profile == profile)
                    vm.chat.value.sessionId
            } }
            val marker = "BEACON_DELIVERY_" + UUID.randomUUID().toString().replace("-", "")
            var decor: View? = null
            scenario.onActivity { decor = it.window.decorView }
            val rootView = checkNotNull(decor)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val controls = JSONObject()
            fun all(node: SemanticsNode): Sequence<SemanticsNode> = sequenceOf(node) + node.children.asSequence().flatMap { all(it) }
            // Use the existing assist-overlay semantics pattern: Compose idling can outlast a live Working interval.
            fun <T> onNodes(read: (List<SemanticsNode>) -> T): T {
                var value: Result<T>? = null
                instrumentation.runOnMainSync { value = runCatching {
                    fun views(view: View): Sequence<View> = sequenceOf(view) + ((view as? ViewGroup)?.let { group ->
                        (0 until group.childCount).asSequence().flatMap { views(group.getChildAt(it)) }
                    } ?: emptySequence())
                    val roots = views(rootView).filterIsInstance<ViewRootForTest>().toList()
                    val nodes = roots.flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode).toList() }
                    controls.put("semantics_roots", roots.size).put("semantics_nodes", nodes.size)
                    read(nodes)
                } }
                return checkNotNull(value).getOrThrow()
            }
            fun probe(nodes: List<SemanticsNode>, text: String): Pair<SemanticsNode?, SemanticsNode?> {
                val inputs = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == "message-input" }
                val sends = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == "message-send" }
                val input = inputs.filter { it.layoutInfo.isPlaced }.singleOrNull()
                val send = sends.filter { it.layoutInfo.isPlaced }.singleOrNull()
                val editors = input?.let { all(it).filter { child -> child.layoutInfo.isPlaced && child.config.contains(SemanticsProperties.EditableText) }.toList() }.orEmpty()
                val actions = send?.let { all(it).filter { child -> child.layoutInfo.isPlaced && child.config.getOrNull(SemanticsActions.OnClick)?.action != null }.toList() }.orEmpty()
                val editor = editors.singleOrNull(); val button = actions.singleOrNull()
                val inputEnabled = input != null && editor != null && !input.config.contains(SemanticsProperties.Disabled) && !editor.config.contains(SemanticsProperties.Disabled)
                val sendEnabled = send != null && button != null && !send.config.contains(SemanticsProperties.Disabled) && !button.config.contains(SemanticsProperties.Disabled)
                controls.put("input_tags", inputs.size).put("input_placed", inputs.count { it.layoutInfo.isPlaced }).put("input_editors", editors.size).put("input_enabled", inputEnabled)
                    .put("set_text_available", editor?.config?.getOrNull(SemanticsActions.SetText)?.action != null)
                    .put("send_tags", sends.size).put("send_placed", sends.count { it.layoutInfo.isPlaced }).put("send_actions", actions.size).put("send_enabled", sendEnabled)
                    .put("displayed_text_matches", editor?.config?.getOrNull(SemanticsProperties.EditableText)?.text == text).put("composer_text_matches", vm.composer.value.text == text)
                return editor?.takeIf { inputEnabled } to button?.takeIf { sendEnabled }
            }
            fun awaitUi(stage: String, ready: () -> Boolean) {
                controls.put("stage", stage)
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!ready()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "send_${stage}_not_ready" }
                    compose.mainClock.advanceTimeByFrame()
                    Thread.sleep(20)
                }
            }
            fun send(text: String, whileWorking: Boolean = false) {
                // Selection state can finish before the editor has recomposed with its new conversation guard.
                if (!whileWorking) compose.waitForIdle()
                check(vm.chat.value.sessionId == session && vm.ui.value.profile == profile && vm.chat.value.uncertain.isBlank() && vm.composer.value.text.isBlank()) { "send_context_or_draft_changed" }
                controls.put("set_text_attempts", 0).put("click_attempts", 0)
                awaitUi("input") { onNodes { nodes ->
                    val input = probe(nodes, text).first ?: return@onNodes false
                    val setText = input.config.getOrNull(SemanticsActions.SetText)?.action ?: return@onNodes false
                    check(vm.chat.value.sessionId == session && vm.ui.value.profile == profile && vm.chat.value.uncertain.isBlank() && vm.composer.value.text.isBlank()) { "send_context_or_draft_changed" }
                    controls.put("set_text_attempts", controls.getInt("set_text_attempts") + 1)
                    check(setText.invoke(AnnotatedString(text))) { "input_action_rejected" }
                    controls.put("composer_updated_immediately", vm.composer.value.text == text)
                    true
                } }
                awaitUi("click") { onNodes { nodes ->
                    val (input, button) = probe(nodes, text)
                    if (input == null || button == null || input.config.getOrNull(SemanticsProperties.EditableText)?.text != text) return@onNodes false
                    check(vm.chat.value.sessionId == session && vm.ui.value.profile == profile && vm.composer.value.text == text && vm.chat.value.uncertain.isBlank()) { "send_context_or_draft_changed" }
                    check(!whileWorking || vm.chat.value.streamId.isNotBlank() && vm.chat.value.status.isNotBlank()) { "queued_send_requires_current_work" }
                    controls.put("click_attempts", controls.getInt("click_attempts") + 1)
                    check(button.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) { "send_action_rejected" }
                    true // Never retry an invoked Send, even when its outcome is unknown.
                } }
            }
            fun confirmed(token: String) = vm.chat.value.messages.any { it.role == "assistant" && it.text.contains(token) }
            val ordinary = "${marker}_ORDINARY"
            val long = "${marker}_LONG"
            val queued = "${marker}_QUEUED"
            val afterCompletion = "${marker}_AFTER_COMPLETION"
            val start = SystemClock.elapsedRealtime()
            fun elapsed() = SystemClock.elapsedRealtime() - start
            val streamAt = AtomicLong(-1); val workingAt = AtomicLong(-1); val longFinalAt = AtomicLong(-1)
            val evidence = JSONObject().put("run", UUID.randomUUID().toString()).put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE).put("test_revision", "chat_completed_retirement_4").put("passed", false)
            val output = AtomicFile(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "chat-delivery-live.json"))
            var phase = "ready"
            fun save(step: String) {
                phase = step
                val state = vm.chat.value
                evidence.put("phase", step).put("elapsed_ms", elapsed()).put("same_session", state.sessionId == session).put("profile_matches", vm.ui.value.profile == profile)
                    .put("stream_present", state.streamId.isNotBlank()).put("status_present", state.status.isNotBlank()).put("uncertain_present", state.uncertain.isNotBlank())
                    .put("queued_count", state.queued.size).put("stream_observed_ms", streamAt.get()).put("working_observed_ms", workingAt.get()).put("long_final_observed_ms", longFinalAt.get())
                    .put("ordinary_final", confirmed(ordinary)).put("long_final", confirmed(long)).put("queued_final", confirmed(queued))
                    .put("after_completion_final", confirmed(afterCompletion)).put("controls", controls)
                val stream = output.startWrite()
                try { stream.write(evidence.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
                catch (error: Throwable) { output.failWrite(stream); throw error }
            }
            var observing: Job? = null
            try {
                save("ordinary_send"); send("Reply with exactly $ordinary")
                save("ordinary_clicked")
                compose.waitUntil(120_000) { confirmed(ordinary) && vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank() }
                assertTrue("The admitted ordinary input clears its draft", vm.composer.value.text.isBlank())
                observing = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    vm.chat.collect { state ->
                        if (state.streamId.isNotBlank()) streamAt.compareAndSet(-1, elapsed())
                        if (state.streamId.isNotBlank() && state.status.isNotBlank()) workingAt.compareAndSet(-1, elapsed())
                        if (state.messages.any { it.role == "assistant" && it.text.contains(long) }) longFinalAt.compareAndSet(-1, elapsed())
                    }
                }
                evidence.put("long_send_start_ms", elapsed()); save("long_send")
                send("Use a harmless terminal tool to wait for 8 seconds, then reply with exactly $long")
                evidence.put("long_send_return_ms", elapsed()).put("long_final_at_send_return", confirmed(long)); save("long_clicked")
                compose.waitUntil(30_000) { vm.chat.value.streamId.isNotBlank() && vm.chat.value.status.isNotBlank() }
                save("working"); send("/queue Reply with exactly $queued", whileWorking = true)
                save("queued_clicked")
                compose.waitUntil(30_000) { vm.chat.value.queued.isNotEmpty() }
                save("queued")
                if (args.getString("beaconChatDeliveryBackground") == "1") {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    save("background")
                    scenario.moveToState(Lifecycle.State.RESUMED)
                }
                compose.waitUntil(180_000) { confirmed(long) && confirmed(queued) && vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank() }
                assertEquals("No navigation was needed to receive final output", session, vm.chat.value.sessionId)
                assertEquals(profile, vm.ui.value.profile)
                assertEquals("One intended queued input yields one user row", 1, vm.chat.value.messages.count { it.role == "user" && it.text == "Reply with exactly $queued" })
                save("completed_cards_retiring")
                compose.waitUntil(90_000) { vm.chat.value.queued.isEmpty() }
                save("after_completion_send"); send("Reply with exactly $afterCompletion")
                compose.waitUntil(120_000) { confirmed(afterCompletion) && vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank() }
                assertEquals("A follow-up after task completion stays in the same chat", session, vm.chat.value.sessionId)
                assertEquals(1, vm.chat.value.messages.count { it.role == "user" && it.text == "Reply with exactly $afterCompletion" })
                compose.waitUntil(90_000) { vm.chat.value.queued.isEmpty() }
                evidence.put("passed", true); save("complete")
            } finally { observing?.cancelAndJoin(); save(phase) }
        }
    }
}
