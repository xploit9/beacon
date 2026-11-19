package dev.hermes.android.turn

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.TURN_RESTARTED
import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.localizedTurnLine
import dev.hermes.android.data.turnErrorHeadline
import dev.hermes.android.data.turnErrorText
import dev.hermes.android.drainsQueueAfterTerminal
import dev.hermes.android.failedTurnReason
import dev.hermes.android.interruptedTurnLine
import dev.hermes.android.liveAfterEvent
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.savedWaitLine
import dev.hermes.android.statusAfterRestore
import dev.hermes.android.ui.answeredOwnMessageIds
import dev.hermes.android.ui.placeKeptReplies
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TURN-121 follow-up 1 (REVIEW-TURN, Root's decisions 1-8). Synthetic text and ids only. The reviewer's ReviewTurnTest proofs are inverted here.
 * Frame shapes are Hermes' own: `message.complete` from gateway/session_settlement_recovery.py completion_payload (no `partial` key on this path),
 * the failed_turn boundary row with `display_metadata {error, error_surface}` (agent/conversation_loop.py _failed_turn_display_metadata; the demo's
 * rows carry {"layer":"provider","code":"rate_limit","retryable":true,"resets_at":<epoch s>}), and the restart `warning` (RESTART_INTERRUPT_NOTICE).
 */
class TurnFollowUpTest {
    private fun live(s: ChatState, type: String, o: JSONObject): ChatState =
        liveAfterEvent(s, type, o, previousClosed = false, next = LiveAssistantBoundary().after(type, o)) ?: s

    private val now = System.currentTimeMillis()
    private val nowSeconds = now / 1000.0

    private fun failed(code: String, layer: String, retryable: Boolean, text: String = "", resetsAt: Double? = null, message: String? = null) = json(
        "status" to "error", "text" to text, "content" to text, "outcome" to "failed", "failure_reason" to code, "message" to code,
        "error_surface" to json("layer" to layer, "code" to code, "retryable" to retryable, "provider" to "custom", "model" to "example-model").apply {
            if (resetsAt != null) put("resets_at", resetsAt)
            if (message != null) put("message", message)
        })

    private fun headline(event: JSONObject) = turnErrorHeadline(turnErrorText(event, now))

    // ---- Decision 1 (P2-1): the reason never comes from the reply's own words ----

    @Test fun aFailureWithNoPhoneLineNeverGivesTheReplysWordsAsItsReason() {
        // agent/turn_recovery.py _with_delivered_partial: "<shown words>\n\n<failure copy>"; completion_payload sends no `partial` key.
        val text = "Sure, here is the summary of your week.\n\nThe provider returned an error on every attempt. Send /retry to try again."
        val unknown = headline(failed("unknown", "provider", true, text))
        assertFalse(unknown, unknown.contains("summary"))
        assertEquals("Hermes couldn't do that. Try again.", unknown)
        val auth = headline(failed("auth", "auth", false, "Sure, here is the summary of your week."))
        assertFalse(auth, auth.contains("summary"))
    }

    // ---- Decision 2 (P2-2): the cleanup never hides real reply words ----

    private val prompt = json("row_id" to 21001, "role" to "user", "content" to "Synthetic question")
    private val partialBoundary = json("row_id" to 21002, "role" to "assistant", "display_kind" to "failed_turn",
        "content" to "This turn did not complete. Some actions may already have run; verify their effects before resending.")
    private val notProcessed = json("row_id" to 21002, "role" to "assistant", "display_kind" to "failed_turn",
        "content" to "Your request was not processed. Send it again if you still want me to carry it out.")

    private fun saved(s: ChatState, vararg rows: JSONObject) = s.copy(messages = rows.mapIndexed { i, r -> Message.from(r, i, "chat-r") },
        raw = s.raw.copy().put("messages", JSONArray(rows.toList())))
    private fun timeline(chat: ChatState) = placeKeptReplies(chat.messages, chat.raw.rows("_unconfirmed_replies").mapIndexed { i, row ->
        Message.from(row, i, "chat-r") to row.string("_history_user_row_id").takeIf { row.string("_history_row_id").isBlank() }.orEmpty() })

    @Test fun wordsStreamedBeforeAFailureOrARestartStayAboveHermesBoundary() {
        for (boundary in listOf(partialBoundary, notProcessed)) {
            var s = saved(ChatState(sessionId = "chat-r", streamId = "runtime-r", status = "Working"), prompt)
            s = live(s, "token", json("text" to "Half of the real answer the owner was reading"))
            val released = releaseTerminalChat(saved(s, prompt, boundary), "runtime-r", replyConfirmed = false, terminalStatus = "The model is busy. Try again in a moment.")
            val shown = timeline(released)
            assertEquals("the words stay, between the prompt and Hermes' boundary", 3, shown.size)
            assertEquals("21001", shown[0].id)
            assertEquals("Half of the real answer the owner was reading", shown[1].text)
            assertEquals("21002", shown[2].id)
        }
    }

    @Test fun anOlderTurnsKeptReplyStaysWhileTheNewestTurnShowsAFailure() {
        val second = json("row_id" to 21003, "role" to "user", "content" to "Synthetic second question")
        val failedRow = notProcessed.copy().put("row_id", 21004)
        val older = Message("unconfirmed:older", "assistant", "An earlier answer Hermes never saved", displayKind = "unconfirmed_reply")
        val shown = listOf(prompt, second, failedRow).mapIndexed { i, r -> Message.from(r, i, "chat-r") }
        assertTrue(placeKeptReplies(shown, listOf(older to "")).any { it.id == "unconfirmed:older" })
    }

    @Test fun hermesStopCopiesAndReasoningOnlyCopiesAreStillNotShown() {
        val shown = listOf(Message.from(prompt, 0, "chat-r"))
        val copies = listOf(
            "Operation interrupted: retrying API call after error (retry 1/3).",
            "Operation interrupted during retry (provider error, attempt 2/3).",
            "Operation interrupted: waiting for the provider to recover (cycle 1/3).",
        ).mapIndexed { i, t -> Message("unconfirmed:$i", "assistant", t, displayKind = "unconfirmed_reply") to "21001" } +
            (Message("unconfirmed:r", "assistant", "", reasoning = "⏳ rate limited — resets in ~5m", displayKind = "unconfirmed_reply") to "21001")
        assertEquals(listOf("21001"), placeKeptReplies(shown, copies).map { it.id })
    }

    // ---- Decision 3 (P3-1): a message is blue only with its own reply ----

    @Test fun aFailedMessageStaysGreyWhenTheFollowUpAfterItIsAnswered() {
        val rows = listOf(Message("1", "user", "First"),
            Message("2", "assistant", "Your request was not processed. Send it again if you still want me to carry it out.", displayKind = "failed_turn"),
            Message("3", "user", "Second, sent after the failure"))
        assertEquals(setOf("3"), answeredOwnMessageIds(rows, replying = true))
        assertEquals(setOf("3"), answeredOwnMessageIds(rows + Message("4", "assistant", "Second answer"), replying = false))
    }

    // ---- Decision 4 (P3-2): drain only when trying again can help ----

    @Test fun theQueueDrainsOnlyWhenTryingAgainCanHelp() {
        fun drains(event: JSONObject) = drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = false, terminalError = turnErrorText(event, now), nowMs = now)
        assertTrue("busy, retryable, no reset", drains(failed("overloaded", "provider", true)))
        assertFalse("a limit that resets later (the demo's real frame)", drains(failed("rate_limit", "provider", true, resetsAt = nowSeconds + 300)))
        assertFalse("too long", drains(failed("context_overflow", "provider", false)))
        assertFalse("rejected model", drains(failed("model_not_found", "provider", false)))
        assertFalse("out of credits", drains(failed("billing", "billing", false)))
        assertFalse("no error_surface: Hermes said nothing about retrying", drains(json("status" to "error", "message" to "boom")))
        // A reset that has passed no longer holds the queue (a saved frame read later).
        assertTrue(drainsQueueAfterTerminal(false, true, turnErrorText(failed("rate_limit", "provider", true, resetsAt = nowSeconds + 300), now), nowMs = now + 600_000))
        assertFalse(drainsQueueAfterTerminal(false, false, "Interrupted", nowMs = now))
    }

    @Test fun hermesFailedRowCarriesTheReasonSoItSurvivesARenderOrAReopen() {
        val metadata = json("error" to "HTTP 429: provider text the phone never shows", "error_surface" to json("layer" to "provider", "code" to "rate_limit",
            "retryable" to true, "provider" to "custom", "model" to "example-model", "resets_at" to nowSeconds + 300))
        val row = Message.from(notProcessed.copy().put("display_metadata", metadata), 1, "chat-r")
        val reason = failedTurnReason(row)
        assertEquals("The model's usage limit is reached.", turnErrorHeadline(reason))
        assertFalse("Hermes' raw error is never shown", reason.contains("HTTP 429"))
        // A stopped turn's row has no display_metadata: no line under it.
        assertEquals("", failedTurnReason(Message.from(notProcessed, 1, "chat-r")))
        // Device, 21:55: reopened after the limit had reset, the row read "The model is busy". The reason is judged at the row's own time; only the passed reset is dropped.
        val failedAt = nowSeconds - 3600
        val oldMetadata = json("error_surface" to json("layer" to "provider", "code" to "rate_limit", "retryable" to true, "resets_at" to failedAt + 300))
        val oldRow = Message.from(notProcessed.copy().put("display_metadata", oldMetadata).put("timestamp", failedAt), 1, "chat-r")
        val old = failedTurnReason(oldRow, nowMs = now)
        assertEquals("The model's usage limit is reached.", turnErrorHeadline(old))
        assertEquals("The model's usage limit is reached.", localizedTurnLine(old, { it }, clock = { "T" }, english = true, nowMs = now))
    }

    // ---- Decision 5 (P3-3): Hermes' restart interrupt is not the owner's Stop ----

    @Test fun aTurnHermesCutForARestartSaysSoAndOnlyThisPhonesStopIsAStop() {
        val restart = json("status" to "interrupted", "outcome" to "cancelled", "text" to "",
            "warning" to "Gateway restarting: this turn was interrupted and will not be run again. Send your message again once Hermes reconnects.")
        val shutdown = restart.copy().put("warning", "Gateway shutting down: this turn was interrupted and will not be run again. Send your message again once Hermes is back.")
        assertEquals(TURN_RESTARTED, interruptedTurnLine(restart, stoppedHere = false))
        assertEquals(TURN_RESTARTED, interruptedTurnLine(shutdown, stoppedHere = false))
        assertEquals("this phone's Stop", "Interrupted", interruptedTurnLine(restart, stoppedHere = true))
        assertEquals("stopped elsewhere, no restart", "Interrupted", interruptedTurnLine(json("status" to "interrupted"), stoppedHere = false))
        // Either way a person decides what goes next.
        assertFalse(drainsQueueAfterTerminal(false, false, interruptedTurnLine(restart, stoppedHere = false), nowMs = now))
    }

    // ---- Decision 6 (P3-4): the wait line survives a reconnect or reopen ----

    @Test fun theWaitLineIsSavedWithTheLiveFrameAndRestored() {
        val wait = "⏳ rate limited — resets in ~5m, retrying in 300s"
        assertEquals(wait, savedWaitLine(wait))
        assertEquals("", savedWaitLine("Working"))
        assertEquals(wait, statusAfterRestore("Working", json("cursor" to "c", "wait" to wait)))
        assertEquals("a finished turn's frame brings no wait line back", "Working", statusAfterRestore("Working", json("cursor" to "c", "wait" to wait, "terminal" to true)))
        assertEquals("Working", statusAfterRestore("Working", null))
    }

    // ---- Decision 7 (P3-5): one line per Hermes family that was vague ----

    @Test fun eachVagueFamilyHasItsOwnLine() {
        assertEquals("The model's sign-in or API key was refused. Check it in Hermes.", headline(failed("auth", "auth", false)))
        assertEquals("The model's sign-in or API key was refused. Check it in Hermes.", headline(failed("auth_permanent", "auth", false)))
        assertEquals("The connection to the model dropped. Try again.", headline(failed("stream_drop", "streaming", true)))
        assertEquals("Hermes' disk is full.", headline(failed("disk_full", "disk", false)))
        assertEquals("Out of credits for this model.", headline(failed("billing", "billing", false)))
        assertEquals("Out of credits for this model.", headline(failed("billing_unverified", "billing", false)))
        assertEquals("a window plan's limit is a limit", "The model's usage limit is reached.", headline(failed("usage_window", "billing", true, resetsAt = nowSeconds + 3600)))
    }

    // ---- Decision 8 (P3-6): strings ----

    private val catalog: JSONObject by lazy {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    }

    @Test fun theLiveRowsOwnWordsAreTranslatedInEveryLanguage() {
        val languages = catalog.keys().asSequence().filter { it != "en" }.toList()
        assertEquals(15, languages.size)
        for (key in listOf("interrupted", "finishing", "responding", "hermes restarted during this reply. send your next message?")) for (language in languages)
            assertTrue("$language: $key", catalog.getJSONObject(language).optString(key).isNotBlank())
    }

    @Test fun theResetTimeIsDrawnWhenShownWithThePhonesClock() {
        val line = turnErrorText(failed("rate_limit", "provider", true, resetsAt = nowSeconds + 300), now)
        val shown = localizedTurnLine(line, { it }, clock = { "CLOCK" }, english = true, nowMs = now)
        assertEquals("The model's usage limit is reached. Resets at CLOCK.", shown)
        // Shown after the reset has passed: no stale time.
        assertEquals("The model's usage limit is reached.", localizedTurnLine(line, { it }, clock = { "CLOCK" }, english = true, nowMs = now + 600_000))
    }

    @Test fun hermesWaitLineLosesItsMechanics() {
        assertEquals("⏳ waiting on example-model — 30s with no output yet",
            live(ChatState(streamId = "r"), "thinking", json("text" to "⏳ waiting on example-model — 30s with no output yet (auto-reconnect: wall-clock stale watchdog in 45s)")).status)
        assertEquals("⏳ rate limited — resets in ~5m, retrying in 300s",
            live(ChatState(streamId = "r"), "thinking", json("text" to "⏳ rate limited — resets in ~5m, retrying in 300s (attempt 1/3)")).status)
    }

    @Test fun anotherLanguageGetsThePhonesTranslatedLineOverHermesEnglishOne() {
        val line = turnErrorText(failed("usage_window", "billing", true, resetsAt = nowSeconds + 3600, message = "You've hit your weekly limit."), now)
        val italian = mapOf("The model's usage limit is reached." to "Limite raggiunto.", "Resets at {0}." to "Si azzera alle {0}.")
        assertEquals("Limite raggiunto. Si azzera alle T.", localizedTurnLine(line, { italian[it] ?: it }, clock = { "T" }, english = false, nowMs = now))
        assertEquals("You've hit your weekly limit. Resets at T.", localizedTurnLine(line, { it }, clock = { "T" }, english = true, nowMs = now))
    }
}
