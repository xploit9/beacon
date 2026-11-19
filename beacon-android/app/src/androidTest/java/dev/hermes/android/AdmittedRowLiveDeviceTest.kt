package dev.hermes.android

import android.os.Bundle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * CHAT-121 follow-up 5, live on the demo Hermes: once Hermes admits a message it shows as sent, as Hermes' own saved row, at once, whatever the turn does
 * after (Root's rule, as with Telegram). Each attempt is a new chat with one synthetic message that asks for an 8-second wait in a terminal tool before
 * a short reply, so the turn runs well past the row (CHATFIX-121 follow-up 6); the chat is read every 250 ms from the send to the end
 * of its turn (or a stop), and the times are reported: admitted, the turn started, the saved row shown in place of the phone's copy, the first reply
 * words, the end. The chat's default model goes first; when it does not answer (a model at its usage limit retries for minutes, the case the rule is
 * for), the turn is stopped and the next model Hermes offers is picked for a new chat, until one answers (at most four).
 * Opt-in: `beaconLiveService=1` with the demo sign-in staged ([DemoCallSignIn]). Every chat is stopped and deleted through the app.
 */
class AdmittedRowLiveDeviceTest {
    @get:Rule(order = 0) val signIn = DemoCallSignIn()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<BeaconActivity>()

    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })

    private class Attempt(val label: String) {
        var admittedMs = -1L; var startedMs = -1L; var rowMs = -1L; var wordsMs = -1L; var doneMs = -1L
        var runningAtRow = false; var stamped = false; var users = 0; var replies = 0; var settled = ""
        val statuses = linkedSetOf<String>()
        override fun toString() = "$label admitted=$admittedMs started=$startedMs row=$rowMs running_at_row=$runningAtRow stamped=$stamped words=$wordsMs done=$doneMs " +
            "users=$users replies=$replies copy=$settled statuses=${statuses.joinToString("|") { it.take(70) }}"
    }

    @Test(timeout = 1_200_000) fun anAdmittedMessageIsItsSavedRowAtOnce() = runBlocking<Unit> {
        assumeTrue("Opt-in: --arg beaconLiveService=1", InstrumentationRegistry.getArguments().getString("beaconLiveService") == "1" && signIn.staged)
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        withTimeout(90_000) { while (!(vm.ui.value.connected && vm.client != null && !vm.ui.value.restoringSession)) delay(100) }
        // The models Hermes offers this profile, from Hermes itself (never from its config file).
        val options = vm.client!!.detached().let { c -> try { c.get("/api/models") } finally { c.closeNative() } }
        val default = options.string("model")
        val others = options.rows("groups").flatMap { group -> group.rows("models").map { group.string("provider_id") to it.string("id") } }
            .filter { (_, model) -> model.isNotBlank() && model != default }.distinct()
        status("admitted_models", "default=$default provider=${options.string("provider")} others=${others.size} first=${others.take(12)}")
        val candidates = listOf<Pair<String, String>?>(null) + others.take(3)
        val attempts = mutableListOf<Attempt>()
        for (candidate in candidates) {
            val attempt = attempt(vm, candidate)
            attempts += attempt
            status("admitted_attempt_${attempts.size}", attempt.toString())
            if (attempt.doneMs >= 0 && attempt.wordsMs >= 0) break
        }
        // Every attempt: the saved row is shown in place of the copy within seconds of the admission, while the turn still runs, before any reply words,
        // and it is the row Hermes stamped with this send's id.
        for (a in attempts) {
            assertTrue("row shown: $a", a.rowMs >= 0 && a.admittedMs >= 0 && a.rowMs - a.admittedMs <= 15_000)
            assertTrue("row shown while the turn runs: $a", a.runningAtRow && (a.doneMs < 0 || a.rowMs < a.doneMs))
            assertTrue("row shown before the reply's words: $a", a.wordsMs < 0 || a.rowMs <= a.wordsMs)
            assertTrue("the row Hermes stamped with this send: $a", a.stamped)
        }
        // One model answered: the turn ended with one user row and the reply, and the copy settled from the turn's own receipt.
        val answered = attempts.lastOrNull { it.doneMs >= 0 && it.wordsMs >= 0 }
        status("admitted_summary", "attempts=${attempts.size} answered=${answered?.label}")
        assertTrue("a model answered: $attempts", answered != null)
        assertEquals("one user row: $answered", 1, answered!!.users)
        assertTrue("the reply row: $answered", answered.replies >= 1)
    }

    private suspend fun attempt(vm: BeaconViewModel, candidate: Pair<String, String>?): Attempt {
        val tag = java.util.UUID.randomUUID().toString().take(6)
        val title = "Admitted row check $tag"
        val request = "admitted-row-check-" + java.util.UUID.randomUUID()
        val attempt = Attempt(candidate?.let { "${it.first}/${it.second}" } ?: "default")
        var id = ""
        try {
            val previous = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to title, "request_id" to request)) }.join()
            withTimeout(30_000) { while (!(vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.projectionReady && !vm.ui.value.newChatOpening)) delay(100) }
            id = vm.chat.value.sessionId
            if (candidate != null) {
                withContext(Dispatchers.Main.immediate) { vm.pickModel(candidate.second, candidate.first) }
                withTimeout(30_000) { while (vm.modelSaving.value.isNotBlank() || vm.ui.value.notice == "Applying model…") delay(100) }
                status("admitted_pick_${attempt.label.takeLast(40).replace(Regex("[^A-Za-z0-9_.-]"), "_")}", "notice='${vm.ui.value.notice.take(100)}' model='${vm.chat.value.raw.string("model")}'")
            }
            // The turn has to outlast the saved row for "shown while the turn runs" to mean anything: a plain short reply ends within one 250 ms read
            // of the row (FINAL-121: about 2 s, 2 of 4 takes failed on that alone). A harmless wait in a terminal tool (as ChatDeliveryDeviceTest asks)
            // keeps it running for 8 s or more, and nothing written before the tool keeps the reply's words after the row.
            val text = "First use a harmless terminal tool to wait for 8 seconds, writing nothing before it; then reply with one short sentence. Synthetic check $tag"
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(text)) }
            val began = android.os.SystemClock.elapsedRealtime()
            fun now() = android.os.SystemClock.elapsedRealtime() - began
            withContext(Dispatchers.Main.immediate) { vm.send() }
            // Every 250 ms until the turn ends, or 60 s with no reply words (a model that does not answer: stopped below).
            var copyId = ""; var submission = ""; var row = ""
            while (true) {
                val chat = vm.chat.value
                if (chat.sessionId == id) {
                    val copy = chat.queued.firstOrNull { it.text == text } ?: chat.queued.firstOrNull { it.id == copyId }
                    if (copy != null) { copyId = copy.id; submission = copy.submissionId }
                    if (attempt.admittedMs < 0 && copy?.admissionId?.isNotBlank() == true) attempt.admittedMs = now()
                    if (attempt.startedMs < 0 && copy?.admissionStatus == "started") attempt.startedMs = now()
                    val shown = copy?.rowId?.takeIf { r -> r.isNotBlank() && chat.messages.any { it.id == r && it.role == "user" } }
                    if (attempt.rowMs < 0 && shown != null) {
                        attempt.rowMs = now(); row = shown
                        attempt.runningAtRow = chat.streamId.isNotBlank()
                        attempt.stamped = chat.raw.rows("messages").firstOrNull { it.string("row_id", it.string("_row_id")) == shown }?.let(::stampOf) == submission
                    }
                    val replied = chat.liveText.isNotBlank() || chat.liveSealed.isNotEmpty() || (row.isNotBlank() && chat.messages.dropWhile { it.id != row }.any(::isReply))
                    if (attempt.wordsMs < 0 && replied) attempt.wordsMs = now()
                    if (chat.status.isNotBlank()) attempt.statuses += chat.status
                    if (attempt.admittedMs >= 0 && attempt.doneMs < 0 && chat.streamId.isBlank() && now() > 3_000 && chat.messages.any(::isReply)) attempt.doneMs = now()
                }
                if (attempt.doneMs >= 0 || attempt.wordsMs < 0 && now() > 60_000 || now() > 240_000) break
                delay(250)
            }
            if (attempt.doneMs >= 0) {
                // The turn's own receipt settles the copy (or the sweep retires it); read where it stands a few seconds on.
                delay(5_000)
                val chat = vm.chat.value
                attempt.users = chat.messages.count { it.role == "user" }
                attempt.replies = chat.messages.count(::isReply)
                attempt.settled = chat.queued.firstOrNull { it.id == copyId }?.let { "${it.state}/${it.outcome}/${it.rowId}" } ?: "retired"
            } else {
                attempt.settled = vm.chat.value.queued.firstOrNull { it.id == copyId }?.let { "${it.state}/${it.outcome}/${it.rowId}" } ?: "none"
            }
        } finally { withContext(NonCancellable) {
            // A model still retrying is stopped, then the chat is deleted through the app's own Delete.
            runCatching { withTimeoutOrNull(15_000) { withContext(Dispatchers.Main.immediate) { vm.cancel() }.join() } }
            val chat = id
            val deleted = chat.isNotBlank() && runCatching { withTimeoutOrNull(45_000) {
                var ok = false
                repeat(4) { if (!ok) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(chat, "/api/session/delete", done = "Deleted") }.join()
                    ok = vm.ui.value.notice == "Deleted"
                    if (!ok) delay(5_000)
                } }
                ok
            } == true }.getOrDefault(false)
            status("admitted_cleanup_${attempt.label.takeLast(40).replace(Regex("[^A-Za-z0-9_.-]"), "_")}", "title='$title' id=$chat deleted=$deleted notice='${vm.ui.value.notice.take(120)}'")
        } }
        return attempt
    }

    /** A reply of the model; Hermes' own "not processed" row of a failed turn is not one. */
    private fun isReply(message: Message) = message.role == "assistant" && message.displayKind != "failed_turn"

    private fun stampOf(row: JSONObject): String {
        val meta = row.opt("display_metadata")
        val fields = meta as? JSONObject ?: (meta as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return fields?.opt("submission_id") as? String ?: ""
    }
}
