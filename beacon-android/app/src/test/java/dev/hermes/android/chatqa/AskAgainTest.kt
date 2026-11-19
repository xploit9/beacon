package dev.hermes.android.chatqa

import dev.hermes.android.core.Draft
import dev.hermes.android.core.PendingSend
import dev.hermes.android.core.Scope
import dev.hermes.android.data.ApiFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * CHATQA-121 follow-up 5 (fails on fix 4): a send whose answer was lost asks Hermes once more under the same identity and payload, as Hermes' own
 * CLI does (hermes_cli/gateway_queue.py submit_queued), when Hermes answers again and when its chat is opened; never for a removed send, a Hermes
 * without session authority or a record without a saved submission id. The rules are called through reflection and the wiring is read from
 * source, so this compiles on fix 4 and fails there.
 */
class AskAgainTest {
    private val module: File by lazy { requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) }
    private fun source(path: String) = File(module, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")
    private fun body(text: String, start: String, end: String) = text.substringAfter(start, "").substringBefore(end).also { assertTrue("found: $start", it.isNotBlank()) }
    private val rules by lazy { Class.forName("dev.hermes.android.BeaconViewModelKt") }
    private fun call(name: String, vararg args: Any): Any? =
        requireNotNull(rules.methods.firstOrNull { it.name == name && it.parameterCount == args.size }) { "missing rule: $name" }.invoke(null, *args)
    private val scope = Scope("https://hermes.example.com", "owner", "default", "chat-a")
    private fun record(vararg fields: Pair<String, Any?>): PendingSend =
        PendingSend.decode(scope, JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }.toString())

    @Test fun askingAgainSendsTheFirstPayloadUnderItsOwnIdentity() {
        val staged = JSONArray().put(JSONObject().put("path", "/tmp/hermes/image-1.png").put("name", "photo.png"))
        val send = record("id" to "send-1", "draft" to Draft("Synthetic words", attachments = listOf(JSONObject().put("name", "photo.png"))).encode(),
            "submission_id" to "submission-1", "model" to "model-x", "provider" to "provider-y", "queued" to true, "staged_attachments" to staged.toString())
        assertEquals("the staged attachments are kept with the record", staged.toString(), PendingSend.decode(scope, send.encode()).let { JSONObject(it.encode()).getString("staged_attachments") })
        val asked = call("askAgainBody", scope, send) as JSONObject
        assertEquals("submission-1", asked.getString("client_request_id"))
        assertEquals("Synthetic words", asked.getString("message"))
        assertEquals("chat-a", asked.getString("session_id"))
        assertEquals("model-x", asked.getString("model")); assertEquals("provider-y", asked.getString("model_provider"))
        assertTrue(asked.getBoolean("queued_model_snapshot"))
        assertTrue("asked again, never staged again", asked.getBoolean("resubmit"))
        assertEquals(staged.toString(), asked.getJSONArray("staged_attachments").toString())
        // The first request names the same fields, so the payload Hermes digests is the same (gateway/session_controls.py submission()).
        val vm = source("BeaconViewModel.kt")
        val first = body(vm, "            val o = c.post(\"/api/chat/start\", json(", "\n")
        for (key in listOf("\"session_id\" to scope.session", "\"message\" to d.text", "\"client_request_id\" to send.submissionId", "\"queued_model_snapshot\" to (queued != null)"))
            assertTrue(key, first.contains(key))
    }

    @Test fun theRouteResendsTheSavedAttachmentsAndKeepsThemWhenTheAnswerIsLost() {
        val route = body(source("data/NativeOperations.kt"), "\"/api/chat/start\", \"/api/chat/steer\" -> {", "\"/api/chat/cancel\" -> {")
        // Chat join: the files are read once, for CHAT-121's checks and for staging; a resubmit has none.
        assertTrue("no new staging when asked again", route.contains("val files = if (resubmit) emptyList() else d.rows(\"attachments\")") &&
            route.contains("for (attachment in files) {"))
        assertTrue("the saved staged attachments go", route.contains("canonicalAttachments.put(staged.getJSONObject(index))"))
        assertTrue("only a Hermes with session authority is asked again", route.contains("if (!lease.authority) throw NativeTurnNotSent(\"This Hermes is not asked again.\")"))
        assertTrue("a lost answer carries its staged attachments", route.contains("throw NativeSubmitUnknown(canonicalAttachments, error)"))
        val lost = Class.forName("dev.hermes.android.data.NativeSubmitUnknown").declaredConstructors.single().apply { isAccessible = true }
            .newInstance(JSONArray(), IOException("socket")) as Throwable
        assertTrue("a lost answer is still an unknown outcome", call("answerLost", lost) as Boolean)
        assertFalse(call("answerLost", IOException("Not sent")) as Boolean)
    }

    @Test fun neverAskedAgainWithoutASavedIdentityOrAnExactPayload() {
        fun askable(send: PendingSend) = call("askableAgain", send) as Boolean
        assertTrue(askable(record("id" to "s", "draft" to Draft("Text only").encode(), "submission_id" to "submission-2")))
        assertFalse("a record saved without a submission id", askable(record("id" to "legacy-send", "draft" to Draft("Old words").encode())))
        assertFalse("attachments whose staged copies were not saved", askable(record("id" to "s", "draft" to Draft("With file", attachments = listOf(JSONObject().put("name", "a.png"))).encode(), "submission_id" to "submission-3")))
        val vm = source("BeaconViewModel.kt")
        val ask = body(vm, "    private fun askAgainUnconfirmed(): Job? {", "\n    }\n")
        // A removed send has no record and no pending entry left (fix 4), and these are all the ask reads.
        assertTrue(ask.contains("val send = pending[scope.key]?.takeIf(::askableAgain) ?: return null"))
        assertTrue("a Hermes without session authority keeps today's bubble", ask.contains("if (!c.nativeSessionAuthority()) return@launch"))
        assertTrue(ask.indexOf("nativeSessionAuthority()") < ask.indexOf("c.post(\"/api/chat/start\", askAgainBody(scope, send))"))
        val saved = body(vm, "    private suspend fun askAgainSaved(owner: Scope, scope: Scope): Boolean {", "\n    }\n")
        assertTrue(saved.contains("val raw = app.cache.read(scope, \"pending\") ?: return true"))
        assertTrue(saved.contains("if (!c.nativeSessionAuthority()) return false"))
        assertTrue(saved.indexOf("nativeSessionAuthority()") < saved.indexOf("c.post(\"/api/chat/start\", askAgainBody(scope, send))"))
        val remove = body(vm, "    fun removePendingSend() = viewModelScope.launch {", "\n    }\n")
        assertTrue(remove.contains("if (!pending.remove(scope.key, unknown)) return@launch") && remove.contains("deletePending(unknown)"))
    }

    @Test fun onceEachTimeHermesAnswersAgainAndWhenTheChatOpens() {
        val vm = source("BeaconViewModel.kt")
        assertTrue("network back", vm.contains("connect(_ui.value.origin) else { hermesAnsweredAgain(); followMutedReply(); drainOpenQueue(); drainOfflineOutbox() }"))
        assertTrue("Hermes' reach check through", vm.contains("if (hermesWasUnreachable) { hermesWasUnreachable = false; hermesAnsweredAgain() }"))
        assertTrue("connected again", vm.contains("client = next; cachedOwnerScope = null; retireSavedDrafts(null)\n            // Follow-up 5: connected again; a lost answer is asked once more (by the chat's selection, or below).\n            bumpReachEpoch()"))
        assertTrue(vm.contains("            askAgainUnconfirmed()\n            drainOfflineOutbox()\n        } catch (e: Throwable) {"))
        val open = body(vm, "                if (pending.containsKey(scope.key)) {", "\n                }\n")
        assertTrue("the opened chat asks", open.contains("askAgainUnconfirmed()"))
        val ask = body(vm, "    private fun askAgainUnconfirmed(): Job? {", "\n    }\n")
        assertTrue("once per time", ask.contains("askedAgain[send.id] == reachEpoch) return null") && ask.contains("askedAgain[send.id] = reachEpoch"))
        assertTrue("never twice at once", ask.contains("askJobs[send.id]?.isActive == true"))
        assertTrue("never while the first request is on its way", ask.contains("send.submissionId in sendsOnTheirWay"))
        assertFalse("no timer loop", ask.contains("delay(") || ask.contains("while ("))
        // A turn that finished meanwhile: its message and reply are read from Hermes' history.
        val finished = ask.substringAfter("\"terminal\" -> {", "").substringBefore("\"unknown\" -> {")
        assertTrue(finished.contains("renderSession(history, scope)"))
        // Hermes' own row for the submission shows the message, so the phone's copy goes; without that row the copy stays settled.
        assertTrue(finished.contains("send.submissionId) != null) { updateQueued(scope, send.id) { null }; forgetPendingAttachments(scope, send.submissionId) }"))
        val row = { id: Any, meta: Any? -> JSONObject().put("role", "user").put("row_id", id).put("display_metadata", meta ?: JSONObject.NULL) }
        val rows = listOf(row(11, JSONObject().put("submission_id", "other")), row(12, JSONObject().put("submission_id", "submission-9")),
            row(13, "{\"submission_id\": \"submission-10\"}"), JSONObject().put("role", "assistant").put("row_id", 14).put("display_metadata", JSONObject().put("submission_id", "submission-11")))
        assertEquals("12", call("submissionRowId", rows, "submission-9"))
        assertEquals("a row read with its metadata still encoded", "13", call("submissionRowId", rows, "submission-10"))
        assertEquals("only the person's row", null, call("submissionRowId", rows, "submission-11"))
        assertEquals(null, call("submissionRowId", rows, ""))
        // Lost again: the bubble stays "Not confirmed yet" until the next time.
        assertTrue(ask.contains("if (unconfirmed && sourceScope == scope && request.current() && pending[scope.key]?.id == send.id) _chat.update { it.copy(uncertain = send.id, status = \"\") }"))
        // The lost answer of a send is asked the next time Hermes answers, not in the same one.
        val send = body(vm, "            val lostAnswer = attempted && answerLost(e)", "            val notSent = e is NativeTurnNotSent || requestNeverSent(e)")
        assertTrue(send.contains("askedAgain[send.id] = reachEpoch") && send.contains("rememberOutbox(scope)"))
        val look = body(vm, "    private fun lookForHermesAfterLostAnswer(origin: String) {", "\n    }\n")
        assertTrue(look.contains("if (answered && client === active) { hermesAnsweredAgain(); drainOfflineOutbox() }") && !look.contains("while ("))
    }

    @Test fun hermesHoldingAnotherPayloadShowsItsRowAndSendsNothing() {
        val refusal = Class.forName("dev.hermes.android.data.NativeRefusal").declaredConstructors.single().apply { isAccessible = true }
        fun refused(message: String, field: String) = refusal.newInstance(4001, message, field) as Throwable
        assertTrue(call("admissionConflict", IOException("wrapped", refused("admission_conflict", "request_id"))) as Boolean)
        // Only the conflict naming the request id proves Hermes holds it (hermes_state_runtime.py:35-38); a route, binding or lineage conflict does not.
        assertFalse(call("admissionConflict", ApiFailure(4001, "admission_conflict")) as Boolean)
        assertFalse(call("admissionConflict", refused("admission_conflict", "sessions")) as Boolean)
        assertFalse(call("admissionConflict", ApiFailure(4001, "session not found")) as Boolean)
        val gateway = source("data/NativeGateway.kt")
        assertTrue("the gateway keeps the refusal's field", gateway.contains("else NativeRefusal(it.optInt(\"code\", 500), it.optString(\"message\", \"Native operation failed\"), field)"))
        val vm = source("BeaconViewModel.kt")
        val ask = body(vm, "    private fun askAgainUnconfirmed(): Job? {", "\n    }\n")
        assertTrue(ask.contains("if (admissionConflict(e) && request.current() && pending[scope.key] === send) { unconfirmed = false; forgetAnsweredCopy(scope, send, c, request) }"))
        val forget = body(vm, "    private suspend fun forgetAnsweredCopy(", "\n    }\n")
        assertTrue("Hermes' history is shown", forget.contains("renderSession(c.get(\"/api/session\", mapOf(\"session_id\" to scope.session)), scope)"))
        assertTrue(forget.contains("deletePending(send)") && forget.contains("updateQueued(scope, send.id) { null }"))
        for (step in listOf("/api/chat/start", "drainQueued", "reviewPendingSend", "rememberOutbox")) assertFalse("never $step", forget.contains(step))
        val saved = body(vm, "    private suspend fun askAgainSaved(owner: Scope, scope: Scope): Boolean {", "\n    }\n")
        assertTrue(saved.contains("admissionConflict(e)"))
    }

    @Test fun theOutboxSavesEachSendAndSettlesALostOneBeforeAnythingBehindIt() {
        val vm = source("BeaconViewModel.kt")
        val waiting = body(vm, "    private suspend fun sendWaiting(owner: Scope, scope: Scope): Boolean {", "\n    }\n")
        assertTrue("settled first", waiting.indexOf("if (!askAgainSaved(owner, scope)) return true") in 0 until waiting.indexOf("nextOfflineToSend(draft)"))
        val save = waiting.indexOf("optionalLocalSave { app.cache.write(scope, \"pending\", record.encode(), true) }")
        assertTrue("saved before it goes", save in 0 until waiting.indexOf("val o = c.post(\"/api/chat/start\""))
        assertTrue("a lost answer is kept to be asked", waiting.contains("if (lostAnswer) { askedAgain[record.id] = reachEpoch; optionalLocalSave { app.cache.write(scope, \"pending\", record.copy(staged = lostStaged).encode(), true) } }"))
        assertTrue("and holds the box", waiting.contains("if (lostAnswer) return true"))
    }
}
