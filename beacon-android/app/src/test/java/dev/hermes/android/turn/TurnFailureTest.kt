package dev.hermes.android.turn

import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.LiveAssistantBoundary
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.turnErrorText
import dev.hermes.android.drainsQueueAfterTerminal
import dev.hermes.android.liveAfterEvent
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.ui.answeredOwnMessageIds
import dev.hermes.android.ui.placeKeptReplies
import dev.hermes.android.ui.replyWordsShown
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TURN-121: what a reply that is slow, failing or failed looks like in the chat (CHATQA-121 D2, D3, D1, D9). Synthetic text and ids only.
 *
 * Frame shapes are Hermes' own: a `thinking.delta` frame is `thinking_callback` (gateway/local_viewer_events.py:44), which carries the spinner face
 * (agent/turn_iteration_prep.py:324), an empty clear (agent/turn_api_call.py:32) or an explained wait (agent/turn_recovery.py:1508); each replaces the
 * last. A failed turn ends with `message.complete` built by gateway/session_settlement_recovery.py::completion_payload: `status` "error", `text` (Hermes'
 * chat copy), `error_surface` from agent/error_surface.py and, for a local viewer, `failure_reason`. Hermes closes the turn with an assistant row whose
 * display_kind is "failed_turn" (agent/turn_failure_copy.py).
 */
class TurnFailureTest {
    private fun live(s: ChatState, type: String, o: JSONObject): ChatState =
        liveAfterEvent(s, type, o, previousClosed = false, next = LiveAssistantBoundary().after(type, o)) ?: s

    private val waitLine = "⏳ rate limited — resets in ~5m, retrying in 300s (attempt 1/3)"

    // ---- D2: Hermes' wait line is the live status, never Reasoning ----

    @Test fun hermesWaitLineIsTheLiveStatusAndNeverReasoning() {
        var s = ChatState(sessionId = "chat-1", streamId = "runtime-1", status = "Working")
        s = live(s, "thinking", json("text" to "ヽ(>∀<☆)☆ processing..."))
        assertEquals("the spinner face says Hermes is thinking", "Thinking", s.status)
        assertEquals("a spinner face is not reasoning", "", s.liveReasoning)
        s = live(s, "thinking", json("text" to waitLine))
        // Follow-up 1 (decision 8): its plain first part, without "(attempt 1/3)".
        assertEquals("Hermes' own wait line is the live status while it is current", "⏳ rate limited — resets in ~5m, retrying in 300s", s.status)
        assertEquals("the wait line is not reasoning", "", s.liveReasoning)
        s = live(s, "thinking", json("text" to "(¬‿¬) deliberating..."))
        assertEquals("the next spinner frame replaces the wait line", "Thinking", s.status)
        s = live(s, "thinking", json("text" to waitLine))
        s = live(s, "thinking", json("text" to ""))
        assertEquals("Hermes' empty frame clears the wait line", "Thinking", s.status)
        assertEquals("", s.liveReasoning)
        // Real reasoning stays reasoning, and the first words end every wait.
        s = live(s, "reasoning", json("text" to "Planning the answer"))
        assertEquals("Planning the answer", s.liveReasoning)
        s = live(s, "thinking", json("text" to waitLine))
        assertEquals("Planning the answer", s.liveReasoning)
        s = live(s, "token", json("text" to "The answer"))
        assertEquals("Responding", s.status)
        assertEquals("The answer", s.liveText)
    }

    @Test fun otherExplainedWaitsShowTooAndAnEmptyFrameKeepsAnOrdinaryStatus() {
        for ((line, shown) in listOf("⚠ no output from provider for 900s — reconnecting..." to "⚠ no output from provider for 900s — reconnecting...",
            "↻ model returned reasoning with no final answer — asking it to continue (1/3)" to "↻ model returned reasoning with no final answer — asking it to continue",
            "⚙ processing prompt — 31%" to "⚙ processing prompt — 31%", "⏳ waiting on example-model — 30s with no output yet" to "⏳ waiting on example-model — 30s with no output yet")) {
            assertEquals(line, shown, live(ChatState(streamId = "runtime-1", status = "Thinking"), "thinking", json("text" to line)).status)
        }
        // An empty frame while a tool runs is not a wait ending: the tool's status stays.
        assertEquals("Using Terminal", live(ChatState(streamId = "runtime-1", status = "Using Terminal"), "thinking", json("text" to "")).status)
    }

    // ---- D2: ticks turn blue only once answer words arrive ----

    @Test fun ticksTurnBlueOnlyOnceTheFirstAnswerWordsArrive() {
        val rows = listOf(Message("1", "user", "Question"))
        fun answered(chat: ChatState) = answeredOwnMessageIds(rows, replyWordsShown(chat))
        assertTrue("an open stream alone is not an answer", answered(ChatState(streamId = "runtime-1", status = "Working")).isEmpty())
        assertTrue("Thinking is not an answer", answered(ChatState(streamId = "runtime-1", liveReasoning = "Planning", status = "Thinking")).isEmpty())
        assertTrue("a wait line is not an answer", answered(ChatState(streamId = "runtime-1", status = waitLine)).isEmpty())
        assertTrue("tool progress alone is not an answer", answered(ChatState(streamId = "runtime-1", liveTools = listOf(json("name" to "terminal")), status = "Using Terminal")).isEmpty())
        assertEquals("the first words answer it", setOf("1"), answered(ChatState(streamId = "runtime-1", liveText = "The answer")))
        assertEquals("words said before a tool step answer it", setOf("1"), answered(ChatState(streamId = "runtime-1", liveSealed = listOf("Looking it up"))))
    }

    @Test fun hermesNotProcessedRowIsNotAnAnswer() {
        // Device, 17:04: after a failed and a stopped turn both messages showed "Answered" because Hermes' failed_turn row counted as a reply.
        val failed = Message("2", "assistant", "Your request was not processed. Send it again if you still want me to carry it out.", displayKind = "failed_turn")
        assertTrue(answeredOwnMessageIds(listOf(Message("1", "user", "Question"), failed), replying = false).isEmpty())
        // Follow-up 1 (decision 3): Hermes' failed_turn row ends the walk, so a later reply never answers what Hermes closed as failed.
        assertTrue(answeredOwnMessageIds(listOf(Message("1", "user", "Question"), failed, Message("3", "assistant", "Later words")), replying = false).isEmpty())
        assertEquals(dev.hermes.android.ui.SendTick.Delivered,
            dev.hermes.android.ui.localSendTick(dev.hermes.android.core.QueuedMessage("Hello", state = "settled", outcome = "failed"), answered = false))
    }

    // ---- D3: the failure line says the real reason ----

    private val nowSeconds = System.currentTimeMillis() / 1000.0

    /** message.complete as completion_payload builds it for a failed turn (no `error` key on this path). */
    private fun failedTurn(code: String, layer: String, retryable: Boolean, text: String, resetsAt: Double? = null, message: String? = null) = json(
        "status" to "error", "text" to text, "content" to text, "outcome" to "failed", "failure_reason" to code,
        // NativeOperations.stream: the app's `message` is Hermes' failure_reason.
        "message" to code,
        "error_surface" to json("layer" to layer, "code" to code, "retryable" to retryable, "provider" to "custom", "model" to "example-model", "provider_label" to "Custom endpoint").apply {
            if (resetsAt != null) put("resets_at", resetsAt)
            if (message != null) put("message", message)
        })

    // Hermes' own chat copy for these failures (agent/turn_failure_copy.py): it names slash commands and CLI steps, so it is not a phone line.
    private val exhaustedCopy = "Custom endpoint rate-limited every one of 3 attempts — its usage limit resets in 5m. Send /retry after that, or switch models with /model. " +
        "To avoid this in future, add a backup provider with `hermes fallback add`.\n\nProvider said: HTTP 429: usage limit reached"
    private val overflowCopy = "This conversation has grown too long for example-model to read, and Hermes couldn't shrink it enough automatically. Start a new session with /new " +
        "(your history is kept), or try /compress once more. Switching to a model with a bigger context window also works."

    // Follow-up 1: the line carries Hermes' data (retry, reset time, its own sentence); the chat draws it with localizedTurnLine and the snackbar takes turnErrorHeadline.
    private fun headline(event: JSONObject) = dev.hermes.android.data.turnErrorHeadline(turnErrorText(event))
    private fun shown(line: String, english: Boolean = true, table: Map<String, String> = emptyMap()) =
        dev.hermes.android.data.localizedTurnLine(line, { table[it] ?: it }, clock = { "T" }, english = english)

    @Test fun aUsedUpLimitSaysSoWithItsResetAndNeverTryAgain() {
        val line = shown(turnErrorText(failedTurn("rate_limit", "provider", true, exhaustedCopy, resetsAt = nowSeconds + 300)))
        assertEquals("The model's usage limit is reached. Resets at T.", line)
        assertFalse(line, line.contains("Try again", ignoreCase = true))
        // Follow-up 1 (decision 7): billing with no window is out of credits, not a limit that resets.
        assertEquals("Out of credits for this model.", headline(failedTurn("billing", "billing", false, "Your account is out of credits. Run /usage for details.")))
    }

    @Test fun hermesOwnUserLevelLinePreferred() {
        // A usage-window plan's stop line is written for the person (agent/error_surface.py: surface["message"]); in English it wins.
        assertEquals("You've hit your weekly limit.", shown(turnErrorText(failedTurn("usage_window", "billing", true, "HTTP 402", message = "You've hit your weekly limit."))))
        assertEquals("The free tier is full right now.", shown(turnErrorText(failedTurn("free_tier_at_capacity", "provider", true, "HTTP 429", message = "The free tier is full right now."))))
    }

    @Test fun aBusyModelATooLongChatAndATimeoutEachHaveTheirOwnLine() {
        assertEquals("The model is busy. Try again in a moment.", headline(failedTurn("rate_limit", "provider", true, exhaustedCopy)))
        assertEquals("The model is busy. Try again in a moment.", headline(failedTurn("overloaded", "provider", true, "Custom endpoint reported it was overloaded on all 3 attempts — Send /retry.")))
        assertEquals("This chat is too long for the model. Start a new chat.", headline(failedTurn("context_overflow", "provider", false, overflowCopy)))
        assertEquals("The model took too long to answer. Try again.", headline(failedTurn("timeout", "provider", true, "Custom endpoint didn't respond in time on any of 3 attempts — Send /retry.")))
    }

    @Test fun aTurnThatFailsMidAnswerNeverShowsTheReplysWordsAsTheReason() {
        // completion_payload's text is the turn's response, which can be the reply's own words when the model failed partway: never the reason.
        assertEquals("The model is busy. Try again in a moment.", headline(failedTurn("server_error", "provider", true, "Sure, here is the summary.")))
        assertEquals("This chat is too long for the model. Start a new chat.", headline(failedTurn("context_overflow", "provider", false, "Sure, here is the summary.")))
        for (code in listOf("interpreter_shutdown", "loop_error")) assertEquals(code, "Hermes couldn't do that. Try again.", headline(failedTurn(code, "gateway", false, "Hermes was shutting down and stopped this turn.")))
    }

    @Test fun theResetTimeKeepsItsPlaceInATranslatedLine() {
        val line = turnErrorText(failedTurn("usage_window", "billing", true, "HTTP 402", resetsAt = nowSeconds + 3600))
        val table = mapOf("The model's usage limit is reached." to "Limite raggiunto.", "Resets at {0}." to "Si azzera alle {0}.")
        assertEquals("Limite raggiunto. Si azzera alle T.", shown(line, english = false, table = table))
        assertEquals("the snackbar gets the sentence it can translate", "The model's usage limit is reached.", dev.hermes.android.data.turnErrorHeadline(line))
        assertEquals("a line with no reset is translated whole", "Limite raggiunto.", shown("The model's usage limit is reached.", english = false, table = table))
    }

    @Test fun aFailureThatRetryingCannotFixNeverSaysTryAgain() {
        val auth = turnErrorText(failedTurn("auth", "auth", false, "Custom endpoint rejected your API key, so the model can't be reached. Update it in Settings → Providers, or run `hermes setup` in a terminal."))
        assertFalse(auth, auth.contains("Try again", ignoreCase = true))
        val rejected = turnErrorText(failedTurn("model_not_found", "provider", false, "Model 'x' isn't available. Pick a different model with /model."))
        assertFalse(rejected, rejected.contains("Try again", ignoreCase = true))
    }

    // ---- D1: a reply that never came leaves no bubble ----

    private val prompt = json("row_id" to 15149, "role" to "user", "content" to "Synthetic question")
    private val failedRow = json("row_id" to 15152, "role" to "assistant", "display_kind" to "failed_turn",
        "content" to "Your request was not processed. Send it again if you still want me to carry it out.")

    private fun saved(s: ChatState, vararg rows: JSONObject) = s.copy(messages = rows.mapIndexed { i, r -> Message.from(r, i, "chat-6") },
        raw = s.raw.copy().put("messages", JSONArray(rows.toList())))

    private fun timeline(chat: ChatState) = placeKeptReplies(chat.messages, chat.raw.rows("_unconfirmed_replies").mapIndexed { i, row ->
        Message.from(row, i, "chat-6") to row.string("_history_user_row_id").takeIf { row.string("_history_row_id").isBlank() }.orEmpty() })

    @Test fun aStoppedTurnLeavesNoProgressBubble() {
        // Stop during Hermes' retry wait: abort_turn_on_interrupt's interrupt_text is the interrupted turn's final text (agent/turn_recovery.py:1393).
        var s = saved(ChatState(sessionId = "chat-6", streamId = "runtime-6", status = "Working"), prompt)
        s = live(s, "thinking", json("text" to waitLine))
        s = live(s, "done", json("status" to "interrupted", "outcome" to "cancelled", "text" to "Operation interrupted: retrying API call after error (retry 1/3)."))
        val released = releaseTerminalChat(saved(s, prompt, failedRow), "runtime-6", replyConfirmed = false, terminalStatus = "Interrupted")
        assertEquals(listOf("15149", "15152"), timeline(released).map { it.id })
    }

    @Test fun aFailedTurnLeavesNoReasoningOrProgressBubble() {
        var s = saved(ChatState(sessionId = "chat-6", streamId = "runtime-6", status = "Working"), prompt)
        s = live(s, "thinking", json("text" to "ヽ(>∀<☆)☆ processing..."))
        s = live(s, "thinking", json("text" to waitLine))
        s = live(s, "reasoning", json("text" to "Thinking about the question"))
        val released = releaseTerminalChat(saved(s, prompt, failedRow), "runtime-6", replyConfirmed = false, terminalStatus = "The model's usage limit is reached.")
        assertEquals(listOf("15149", "15152"), timeline(released).map { it.id })
        // Reasoning alone is never kept as a reply, even before Hermes' row is read.
        val early = releaseTerminalChat(s, "runtime-6", replyConfirmed = false, terminalStatus = "")
        assertTrue(early.raw.rows("_unconfirmed_replies").isEmpty())
    }

    @Test fun bubblesKeptByAnEarlierBuildAreNotShownOnReopen() {
        val shown = listOf(Message.from(prompt, 0, "chat-6"), Message.from(failedRow, 1, "chat-6"))
        val progress = Message("unconfirmed:a", "assistant", "", reasoning = "ヽ(>∀<☆)☆ processing...⏳ rate limited — resets in ~5m", displayKind = "unconfirmed_reply")
        val interrupt = Message("unconfirmed:b", "assistant", "Operation interrupted: retrying API call after error (retry 1/3).", displayKind = "unconfirmed_reply")
        assertEquals(listOf("15149", "15152"), placeKeptReplies(shown, listOf(progress to "15149", interrupt to "15149")).map { it.id })
        // A reasoning-only copy kept with no prompt row is not shown either.
        assertEquals(listOf("15149", "15152"), placeKeptReplies(shown, listOf(progress to "")).map { it.id })
    }

    @Test fun realWordsOfAnUnprovenReplyAreStillKept() {
        val shown = listOf(Message.from(prompt, 0, "chat-6"))
        val words = Message("unconfirmed:c", "assistant", "Streamed answer", reasoning = "notes", displayKind = "unconfirmed_reply")
        assertEquals(listOf("15149", "unconfirmed:c"), placeKeptReplies(shown, listOf(words to "15149")).map { it.id })
    }

    // ---- D9: a queued message goes right after a failed turn, as after a success ----

    @Test fun aQueuedMessageIsSentRightAfterAFailureThatTryingAgainCanFix() {
        // Follow-up 1 (decision 4): only when Hermes says retrying can help and no reset lies ahead.
        assertTrue(drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = false, terminalError = turnErrorText(failedTurn("overloaded", "provider", true, ""))))
        assertTrue(drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = true, terminalError = turnErrorText(failedTurn("timeout", "provider", true, ""))))
        // A plain line carries nothing from Hermes about retrying: the follow-up waits in the box.
        assertFalse(drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = false, terminalError = "The model's usage limit is reached."))
        // A turn the owner stopped keeps the queue in the box (it shows Send): stopping is a choice, not a failure.
        assertFalse(drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = false, terminalError = "Interrupted"))
        // A turn nothing can prove ended still holds it.
        assertFalse(drainsQueueAfterTerminal(replyConfirmed = false, terminalAlreadyObserved = false, terminalError = ""))
    }
}
