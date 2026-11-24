package dev.hermes.android.data

import dev.hermes.android.ChatState
import dev.hermes.android.renderedChatStatus
import dev.hermes.android.liveAfterEvent
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Controlled native contracts from bf0203a; no native process, owner records or network. */
private const val RESTART_SPINNER = "( ˘⌣˘)♡ deliberating..."

class ChatDeliveryTest {
    private class Wire(val canonical: Boolean = true, val strictEpoch: Boolean = true, store: AuthStore? = null) : WebSocket.Factory {
        val calls = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val listeners = java.util.concurrent.CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        var epoch = "first-epoch"
        var running = true
        var loseAck = false
        var pending = JSONArray()
        var replay = JSONArray()
        var latest = 3L
        var truncatedAtZero = false
        /** The session's surface in Hermes' info (session_local.local_session_info `source`); null leaves it out. */
        var source: String? = null
        var historyReads = 0
        var history = JSONArray().put(json("id" to 1, "role" to "user", "content" to "Synthetic task"))
        var afterHistoryRead: (() -> Unit)? = null
        val receipts = mutableMapOf<String, JSONObject>()
        val subscribed = CompletableDeferred<Unit>()
        val client = BeaconClient(AuthContext("https://delivery.example", emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard"), store, this, persistContext = store == null) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when (path) {
                    CANONICAL_TICKET_PATH -> json("detail" to "Not Found")
                    "/api/auth/ws-ticket" -> json("ticket" to "synthetic-ticket")
                    "/api/sessions/stored-a" -> json("id" to "stored-a", "profile" to "default", "message_count" to 1, "last_active" to 1)
                    "/api/sessions/stored-a/messages" -> {
                        historyReads++
                        json("session_id" to "stored-a", "profile" to "default", "messages" to JSONArray(history.toString())).also { afterHistoryRead?.invoke() }
                    }
                    else -> JSONObject()
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (path == CANONICAL_TICKET_PATH) 404 else 200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

        fun event(sequence: Long, type: String, payload: JSONObject = json("text" to "Progress"), eventEpoch: String = epoch) =
            json("session_id" to "stored-a", "seq" to sequence, "replay_epoch" to eventEpoch, "type" to type, "payload" to payload)

        fun emit(value: JSONObject) = listeners.last().let { (socket, listener) ->
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to value).toString())
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val owner = this
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); val method = frame.string("method"); val p = frame.getJSONObject("params")
                    calls += frame.copy()
                    val result = when (method) {
                        "session.resume" -> json("session_id" to "stored-a", "stored_session_id" to "stored-a", "messages" to history, "message_count" to history.length(),
                            "running" to running, "execution_generation" to 7, "replay_epoch" to epoch, "last_sequence" to latest, "pending" to pending,
                            "info" to json("profile_name" to "default", "profile_id" to "synthetic-profile").apply { source?.let { put("source", it) } })
                        "session.active_list" -> json("sessions" to JSONArray().put(json("id" to "stored-a", "session_key" to "stored-a", "status" to if (running) "working" else "idle")))
                        "session.events.since" -> {
                            val missing = strictEpoch && p.string("replay_epoch") != epoch || truncatedAtZero && p.optLong("last_seen") == 0L
                            subscribed.complete(Unit)
                            json("epoch" to epoch, "latest_seq" to latest, "truncated" to missing,
                                "events" to if (missing) JSONArray() else JSONArray(replay.objects().filter { it.optLong("seq") > p.optLong("last_seen") }))
                        }
                        "prompt.submit" -> {
                            val receipt = json("admission_id" to "admitted-a", "ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile"),
                                "status" to "queued", "input_id" to p.string("submission_id"))
                            receipts["admitted-a"] = receipt
                            pending = JSONArray().put(receipt)
                            if (loseAck) { listener.onFailure(this, java.io.IOException("Synthetic acknowledgement loss"), null); return true }
                            receipt
                        }
                        "prompt.receipt" -> receipts.getValue(p.string("admission_id")).copy()
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.string("id"), "result" to result).toString())
                    return true
                }
            }
            owner.listeners += socket to listener
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to canonical))).toString())
            return socket
        }
    }

    @Test(timeout = 10_000) fun replayIncludesTheNativeEpochSoUnpersistedProgressIsNotDiscarded() = runBlocking {
        val wire = Wire()
        wire.replay = JSONArray().put(wire.event(1, "message.start")).put(wire.event(2, "tool.start", json("id" to "tool-a", "name" to "synthetic_work")))
            .put(wire.event(3, "message.delta", json("text" to "Still working")))
        val token = CompletableDeferred<String>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { if (it.type == "token") token.complete(it.payload().string("text")) } }
        try {
            val text = withTimeoutOrNull(1500) { token.await() }
            assertEquals("Replay must not replace unsaved progress with an older REST projection", "Still working", text)
            assertEquals(0, wire.historyReads)
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    // Hermes stamps every frame of the turn it runs with that turn's admission (session_events.publish, stamped at the claim in
    // session_authority._stamp_claim). The app keeps that stamp so a started turn can be told from another turn running before it.
    @Test(timeout = 10_000) fun aFrameOfAStartedTurnCarriesTheAdmissionHermesStampedOnIt() = runBlocking {
        val wire = Wire()
        wire.replay = JSONArray().put(wire.event(1, "message.start").put("admission_id", "admitted-a").put("execution_generation", 7))
            .put(wire.event(2, "message.delta", json("text" to "Progress")).put("admission_id", "admitted-a"))
            .put(wire.event(3, "message.delta", json("text" to "Unstamped")))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { seen += it.type to it.payload().string("turn_admission_id") } }
        try {
            withTimeoutOrNull(1500) { while (seen.size < 3) delay(10) }
            assertEquals(listOf("server_turn_started" to "admitted-a", "token" to "admitted-a", "token" to ""), seen.toList())
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun recoveryUsesCanonicalResumeWithoutTheLegacyLazyFlag() = runBlocking {
        val wire = Wire()
        try {
            val answer = runCatching { wire.client.recoveredNativeSession("stored-a") }.getOrNull()
            assertEquals("A foreground silent stream must be able to ask the current authority", "working", answer?.second)
            assertFalse(wire.calls.first { it.string("method") == "session.resume" }.getJSONObject("params").has("lazy"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun lostAcknowledgementFindsTheOriginalAdmissionBySavedInputIdentity() = runBlocking {
        val wire = Wire().apply { loseAck = true }
        try {
            val failure = runCatching { NativeOperations(wire.client).call("/api/chat/start", emptyMap(), "POST",
                json("session_id" to "stored-a", "message" to "Repeatable words", "client_request_id" to "submission-a"), 1024 * 1024) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(requestNeverSent(failure!!))
            val receipt = runCatching { NativeOperations(wire.client).call("/api/mobile/receipts/submission-a", mapOf("session_id" to "stored-a"), "GET", null, 1024 * 1024) }.getOrNull()
            assertEquals("The acknowledged admission is recoverable without a second prompt.submit", "admitted-a", receipt?.string("admission_id"))
            assertEquals(1, wire.calls.count { it.string("method") == "prompt.submit" })
            assertEquals("queued", receipt?.string("status"))
        } finally { wire.client.closeNative() }
    }

    @Test fun refreshingAnActiveProjectionKeepsTheWorkingIndicator() {
        assertEquals("Working", renderedChatStatus(ChatState(streamId = "stored-a", status = "Working"), "stored-a", false))
    }

    @Test fun aPartialSameEpochSnapshotKeepsUnpersistedToolProgress() {
        val boundary = LiveAssistantBoundary().after("tool", json("id" to "tool-a"))
        val before = ChatState(sessionId = "stored-a", streamId = "stored-a", liveText = "Current progress", status = "Working",
            liveTools = listOf(json("id" to "tool-a", "name" to "synthetic_work")))
        val after = liveAfterEvent(before, "state_saved", json("session" to json("active_stream_id" to "stored-a", "messages" to JSONArray())), false, boundary)
        assertEquals("Current progress", after?.liveText)
        assertEquals(1, after?.liveTools?.size)
        assertEquals("Working", after?.status)
    }

    @Test fun aNewEpochSnapshotDiscardsStaleLiveProgressAndKeepsCurrentWorkingState() {
        val before = ChatState(sessionId = "stored-a", streamId = "stored-a", liveText = "Old epoch", status = "Working")
        val after = liveAfterEvent(before, "state_saved", json("replay_reset" to true,
            "session" to json("active_stream_id" to "stored-a", "messages" to JSONArray())), false, LiveAssistantBoundary())
        assertEquals("", after?.liveText)
        assertEquals("Working", after?.status)
    }

    @Test fun anIdleReplayResetReleasesTheOldStreamAndWorkingState() {
        val before = ChatState(sessionId = "stored-a", streamId = "stored-a", liveText = "Old epoch", status = "Working")
        val after = liveAfterEvent(before, "state_saved", json("replay_reset" to true, "session" to json(
            "running" to false, "pending" to JSONArray(), "prompts" to JSONArray(), "active_stream_id" to JSONObject.NULL,
            "messages" to JSONArray().put(json("row_id" to 2, "role" to "assistant", "content" to "Final snapshot")))), false, LiveAssistantBoundary())
        assertEquals("A confirmed idle reset must release the old stream", "", after?.streamId)
        assertEquals("", after?.status)
        assertEquals("", after?.liveText)
        assertEquals("Final snapshot", after?.messages?.last()?.text)
    }

    @Test fun anUnknownAdmissionOnReplayResetStaysUnsettledAndStopsClaimingWorking() {
        val before = ChatState(sessionId = "stored-a", streamId = "stored-a", liveText = "Old epoch", status = "Working", uncertain = "retained-receipt")
        val after = liveAfterEvent(before, "state_saved", json("replay_reset" to true, "session" to json(
            "running" to false, "pending" to JSONArray().put(json("status" to "unknown", "admission_id" to "synthetic-admission")),
            "active_stream_id" to JSONObject.NULL, "messages" to JSONArray())), false, LiveAssistantBoundary())
        assertEquals("An old epoch has no current stream authority", "", after?.streamId)
        assertEquals("A reply Hermes lost in its restart says so (WEDGE-121)", "Hermes restarted during this reply.", after?.status)
        assertEquals("retained-receipt", after?.uncertain)
        assertEquals("", after?.liveText)
        assertEquals("Old epoch", after?.raw?.rows("_unconfirmed_replies")?.singleOrNull()?.string("text"))
    }

    @Test(timeout = 10_000) fun longTaskReplayDeliversProgressAndTheFinalCompletionInOrder() = runBlocking {
        val wire = Wire().apply { latest = 4 }
        wire.replay = JSONArray().put(wire.event(1, "message.start"))
            .put(wire.event(2, "tool.start", json("id" to "tool-a", "name" to "synthetic_work")))
            .put(wire.event(3, "message.delta", json("text" to "Synthetic final")))
            .put(wire.event(4, "message.complete", json("status" to "completed")))
        val types = mutableListOf<String>()
        try {
            withTimeout(1500) { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { types += it.type } }
            assertEquals(listOf("server_turn_started", "tool", "token", "done"), types)
            assertEquals(0, wire.historyReads)
        } finally { wire.client.closeNative() }
    }

    /**
     * TURN-121 follow-up 3, WEDGE's restart run (restart4, 17 Nov 04:10-04:18): after a Hermes restart a bubble "( ˘⌣˘)♡ deliberating..." sat under Hermes'
     * "did not complete" note. That line is Hermes' kawaii spinner (agent/turn_iteration_prep.announce_api_call). Hermes sends it only as `thinking.delta`
     * (agent.thinking_callback, gateway/local_viewer_events.py:44) and clears it with an empty `thinking.delta` once the model answers
     * (agent/turn_api_call.stop_thinking_spinner). The model's own words come as `message.delta`, and its whole commentary as `message.interim`
     * (agent/stream_delivery._deliver_interim: "commentary is model text"). So the frame type tells status from words; the text is never read.
     * The frames run through the app's real mapping in Hermes' order for one model call that started a tool, then the restart's epoch reset
     * (reconciliation.required with an unknown admission) and Hermes' settle with its failed_turn row.
     */
    private suspend fun restartRunFrames(words: List<Pair<String, JSONObject>> = emptyList(), spinnerAfterTool: Boolean = false): List<SseEvent> = coroutineScope {
        val wire = Wire()
        val frames = mutableListOf<JSONObject>()
        fun add(type: String, payload: JSONObject) { frames += wire.event(frames.size + 1L, type, payload) }
        add("message.start", JSONObject())
        add("thinking.delta", json("text" to RESTART_SPINNER))
        words.forEach { (type, payload) -> add(type, payload) }
        add("thinking.delta", json("text" to ""))
        add("tool.start", json("id" to "tool-sleep", "name" to "terminal", "context" to "sleep 600"))
        // The next model call's announcement, had the loop started one before the restart cut it.
        if (spinnerAfterTool) add("thinking.delta", json("text" to RESTART_SPINNER))
        wire.latest = frames.size.toLong()
        wire.replay = JSONArray(frames)
        val seen = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val all = CompletableDeferred<Unit>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { seen += it; if (seen.size == frames.size) all.complete(Unit) } }
        try { withTimeout(3000) { all.await() } } finally { collector.cancel(); wire.client.closeNative() }
        seen.toList()
    }

    private val restartPrompt = json("id" to 1418, "role" to "user", "content" to "Synthetic sleep then answer")
    private val restartToolRow = json("id" to 1419, "role" to "assistant", "content" to "", "tool_calls" to JSONArray().put(json("id" to "tool-sleep", "function" to json("name" to "terminal"))))
    private val restartBoundary = json("id" to 1420, "role" to "assistant", "display_kind" to "failed_turn",
        "content" to "This turn did not complete. Some actions may already have run; verify their effects before resending.")

    /** What the transcript draws after [events], the restart's epoch reset and Hermes' settle, with each kept row as its bubble shows it, plus every live state on the way. */
    private fun afterRestart(events: List<SseEvent>, toolRowWords: String = ""): Pair<List<Pair<String, String>>, List<ChatState>> {
        // Hermes saves the model message, its words and its tool call together, before the tool runs (agent/turn_tool_round.py).
        val toolRow = restartToolRow.copy().put("content", toolRowWords)
        var s = ChatState(sessionId = "stored-a", streamId = "stored-a", status = "Working",
            messages = listOf(dev.hermes.android.Message.from(restartPrompt, 0, "stored-a")), raw = json("session_id" to "stored-a", "messages" to JSONArray().put(restartPrompt)))
        val live = mutableListOf<ChatState>()
        var boundary = LiveAssistantBoundary()
        for (event in events) {
            val payload = event.payload()
            val next = boundary.after(event.type, payload)
            s = liveAfterEvent(s, event.type, payload, boundary.closed, next) ?: s
            boundary = next
            live += s
        }
        fun session(rows: List<JSONObject>, pending: JSONArray) = json("session_id" to "stored-a", "running" to false, "active_stream_id" to JSONObject.NULL,
            "pending" to pending, "messages" to JSONArray(rows))
        // The restart: a new replay epoch and Hermes' snapshot with the admission it cannot settle yet (reconciliation.required -> state_saved, replay_reset).
        s = present(liveAfterEvent(s, "state_saved", json("replay_reset" to true, "session" to session(listOf(restartPrompt, toolRow),
            JSONArray().put(json("status" to "unknown", "admission_id" to "synthetic-admission")))), boundary.closed, boundary))
        live += s
        // Hermes settles the cut turn and closes it with its boundary row.
        s = present(liveAfterEvent(s, "state_saved", json("session" to session(listOf(restartPrompt, toolRow, restartBoundary), JSONArray())), false, LiveAssistantBoundary()))
        val kept = s.raw.rows("_unconfirmed_replies").mapIndexed { i, row ->
            dev.hermes.android.Message.from(row, i, "stored-a") to row.string("_history_user_row_id").takeIf { row.string("_history_row_id").isBlank() }.orEmpty() }
        // A kept reply's bubble draws its words, or its reasoning when it has none (ChatScreen, "unconfirmed-reply:").
        return dev.hermes.android.ui.placeKeptReplies(s.messages, kept).map { it.id to if (it.displayKind == "unconfirmed_reply") it.text.ifBlank { it.reasoning } else it.text } to live
    }

    private fun <T> present(value: T?): T { assertNotNull(value); return value!! }

    @Test(timeout = 10_000) fun hermesSpinnerCutByARestartIsStatusAndNeverAKeptBubble() = runBlocking {
        for (spinnerAfterTool in listOf(false, true)) {
            val (shown, live) = afterRestart(restartRunFrames(spinnerAfterTool = spinnerAfterTool))
            assertEquals("after the settle: the prompt, the tool step and Hermes' own note, no bubble of Hermes' status (spinner after tool: $spinnerAfterTool)",
                listOf("1418" to "Synthetic sleep then answer", "1419" to "", "1420" to restartBoundary.string("content")), shown)
            assertTrue("Hermes' spinner is status, never reply words or reasoning: ${live.map { it.liveText to it.liveReasoning }}",
                live.none { "deliberating" in it.liveText || "deliberating" in it.liveReasoning })
            assertEquals("the spinner shows as the live status line", "Thinking", live[1].status)
        }
    }

    @Test(timeout = 10_000) fun wordsSaidBeforeTheCutToolStepShowOnceFromHermesRowWithNoSpinnerBubble() = runBlocking {
        // Streamed words and whole commentary alike: Hermes saved them with the tool call they came with, so its row shows them and no kept copy does.
        // Words the restart cut after the last saved step are kept (follow-up 4: aCodexReplyCutByARestart…, anAgentLoopReplyCutByARestart…).
        for (words in listOf("message.delta" to json("text" to "I will run it and report back."),
                "message.interim" to json("text" to "I will run it and report back.", "already_streamed" to false))) {
            val (shown) = afterRestart(restartRunFrames(listOf(words), spinnerAfterTool = true), toolRowWords = "I will run it and report back.")
            assertEquals("${words.first}: the words once, in Hermes' row, above its note, with no spinner bubble",
                listOf("1418" to "Synthetic sleep then answer", "1419" to "I will run it and report back.", "1420" to restartBoundary.string("content")), shown)
        }
    }

    /**
     * TURN-121 follow-up 4: the words of a reply a restart or reconnect cut are kept with the tool steps between them, in the order said, until Hermes' saved rows hold them.
     * Frame order from Hermes' source:
     * - Codex app-server runtime (agent/codex_runtime.py make_codex_app_server_event_bridge): each agentMessage streams as `message.delta`; each command item starts and
     *   ends as `tool.start`/`tool.complete` with the id its saved row will carry (_stable_call_id mirrors CodexEventProjector); the turn is saved only at its end
     *   ("this turn will be missing after restart/resume"), as an assistant row of words, an assistant row with the tool call, and the tool row.
     * - Agent loop (agent/turn_tool_round.py): the model message, words and tool calls together, is saved before the tool runs; then `message.delta` with text null
     *   closes the reply box and the tool starts.
     * After a restart Hermes closes the turn with its failed_turn row: "not processed" when only the prompt was saved, the partial note otherwise (agent/turn_failure_copy.py).
     */
    private suspend fun mappedFrames(frames: List<Pair<String, JSONObject>>): List<SseEvent> = coroutineScope {
        val wire = Wire()
        val events = frames.mapIndexed { i, (type, payload) -> wire.event(i + 1L, type, payload) }
        wire.latest = events.size.toLong()
        wire.replay = JSONArray(events)
        val seen = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val all = CompletableDeferred<Unit>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { seen += it; if (seen.size == events.size) all.complete(Unit) } }
        try { withTimeout(3000) { all.await() } } finally { collector.cancel(); wire.client.closeNative() }
        seen.toList()
    }

    private fun toolStart(id: String, name: String = "exec_command") = "tool.start" to json("tool_id" to id, "tool_call_id" to id, "name" to name, "tool_name" to name, "context" to "synthetic")
    private fun toolDone(id: String, name: String = "exec_command") = "tool.complete" to json("tool_id" to id, "tool_call_id" to id, "name" to name, "tool_name" to name, "summary" to "done")
    private fun words(text: String) = "message.delta" to json("text" to text)
    private val cutPrompt = json("id" to 1500, "role" to "user", "content" to "Synthetic check the files")
    private fun savedWords(id: Int, text: String) = json("id" to id, "role" to "assistant", "content" to text)
    private fun savedCall(id: Int, text: String, call: String) = savedWords(id, text).put("tool_calls",
        JSONArray().put(json("id" to call, "type" to "function", "function" to json("name" to "terminal", "arguments" to "{}"))))
    private fun savedResult(id: Int, call: String) = json("id" to id, "role" to "tool", "tool_call_id" to call, "content" to "ok")
    private fun hermesNote(id: Int, text: String) = json("id" to id, "role" to "assistant", "display_kind" to "failed_turn", "content" to text)
    private val notProcessed = "Your request was not processed. Send it again if you still want me to carry it out."
    private val partialNote = "This turn did not complete. Some actions may already have run; verify their effects before resending."

    /** The chat after [events] on a phone holding [phoneRows], a live-frame round trip, the epoch reset with Hermes' saved [resetRows], then each later history read. */
    private fun cutByRestart(events: List<SseEvent>, phoneRows: List<JSONObject>, resetRows: List<JSONObject>, vararg later: List<JSONObject>, midStream: Pair<Int, List<JSONObject>>? = null,
                             ownSend: QueuedMessage? = null): ChatState {
        val (streamed, boundary) = streamed(events, phoneRows, midStream, ownSend)
        var s = streamed
        // A reconnect restores the live frame first (BeaconViewModel: "tools" and "sealed" as saved; the rest of the live state as it was).
        s = s.copy(liveTools = JSONArray(JSONArray(s.liveTools).toString()).objects(), liveSealed = sealedFromFrame(JSONObject(json("sealed" to JSONArray(s.liveSealed)).toString())))
        fun session(rows: List<JSONObject>, pending: JSONArray = JSONArray()) = json("session_id" to "stored-a", "running" to false, "active_stream_id" to JSONObject.NULL,
            "pending" to pending, "messages" to JSONArray(rows))
        s = present(liveAfterEvent(s, "state_saved", json("replay_reset" to true, "session" to session(resetRows, JSONArray().put(json("status" to "unknown", "admission_id" to "synthetic-cut")))),
            boundary.closed, boundary))
        for (rows in later) s = present(liveAfterEvent(s, "state_saved", json("session" to session(rows)), false, LiveAssistantBoundary()))
        return s
    }

    /** The chat after [events] on a phone holding [phoneRows] (with this phone's own send [ownSend], if any), and its live boundary. */
    private fun streamed(events: List<SseEvent>, phoneRows: List<JSONObject>, midStream: Pair<Int, List<JSONObject>>? = null, ownSend: QueuedMessage? = null): Pair<ChatState, LiveAssistantBoundary> {
        var s = ChatState(sessionId = "stored-a", streamId = "stored-a", status = "Working", messages = phoneRows.mapIndexed { i, r -> dev.hermes.android.Message.from(r, i, "stored-a") },
            raw = json("session_id" to "stored-a", "messages" to JSONArray(phoneRows)).apply { ownSend?.let { put("_display_submission_id", it.submissionId) } },
            queued = listOfNotNull(ownSend))
        var boundary = LiveAssistantBoundary()
        for ((index, event) in events.withIndex()) {
            val payload = event.payload()
            val next = boundary.after(event.type, payload)
            s = liveAfterEvent(s, event.type, payload, boundary.closed, next) ?: s
            boundary = next
            // A history read while the turn still runs: the saved front of the turn leaves the live lists.
            midStream?.takeIf { it.first == index }?.let { (_, rows) -> s = present(liveAfterEvent(s, "state_saved", json("session" to json("session_id" to "stored-a",
                "running" to true, "active_stream_id" to "stored-a", "messages" to JSONArray(rows))), boundary.closed, boundary)) }
        }
        return s to boundary
    }

    /** The end of a turn Hermes' receipt did not prove (BeaconViewModel: terminalReplyVisible false) after the phone read Hermes' saved rows [held] with the reply still on screen. */
    private fun unprovenEnd(events: List<SseEvent>, held: List<JSONObject>, phoneRows: List<JSONObject> = listOf(cutPrompt)): ChatState =
        dev.hermes.android.releaseTerminalChat(streamed(events, phoneRows, midStream = events.lastIndex to held).first, "stored-a", replyConfirmed = false, terminalStatus = "")

    /** What the transcript draws: saved rows by role and id, each kept row as its tool cards and words in the order said. */
    private fun drawn(s: ChatState): List<String> {
        val kept = s.raw.rows("_unconfirmed_replies")
        val byId = kept.associateBy { it.string("id") }
        val placed = dev.hermes.android.ui.placeKeptReplies(s.messages, kept.mapIndexed { i, row ->
            dev.hermes.android.Message.from(row, i, "stored-a") to row.string("_history_user_row_id").takeIf { row.string("_history_row_id").isBlank() }.orEmpty() })
        return placed.flatMap { m ->
            val row = byId[m.id] ?: return@flatMap listOf("${m.role}:${m.id}")
            row.rows("live_tools_before").map { "card:${toolIdentity(it)}" } + listOfNotNull(m.text.takeIf(String::isNotBlank)?.let { "kept:$it" }) +
                row.rows("live_tools").map { "card:${toolIdentity(it)}" }
        }
    }

    @Test(timeout = 10_000) fun aCodexReplyCutByARestartKeepsEveryWordAndToolStepInOrder() = runBlocking {
        // Nothing saved: words before, between and after three tool steps, the first step before any words.
        val events = mappedFrames(listOf("message.start" to JSONObject(), toolStart("exec-t0"), toolDone("exec-t0"), words("Looking at the folder. "), words("Two files."),
            toolStart("exec-t1"), toolDone("exec-t1"), words("The first one is fine."), toolStart("exec-t2"), words("Now the second")))
        val s = cutByRestart(events, listOf(cutPrompt), listOf(cutPrompt), listOf(cutPrompt, hermesNote(1501, notProcessed)))
        assertEquals(listOf("user:1500", "card:exec-t0", "kept:Looking at the folder. Two files.", "card:exec-t1", "kept:The first one is fine.", "card:exec-t2",
            "kept:Now the second", "assistant:1501"), drawn(s))
    }

    @Test(timeout = 10_000) fun anAgentLoopReplyCutByARestartShowsSavedWordsOnceAndKeepsTheUnsavedRest() = runBlocking {
        // Hermes saved "Checking the files." with its tool call before the tool ran; the restart cut the next words. Their words may repeat the saved ones:
        // the kept copy is judged by Hermes' ids and its place in the turn, never by its text.
        val saved = listOf(cutPrompt, savedCall(1501, "Checking the files.", "call-1"), savedResult(1502, "call-1"))
        for (after in listOf("Both files are fine", "Checking the files.")) {
            val events = mappedFrames(listOf("message.start" to JSONObject(), "thinking.delta" to json("text" to RESTART_SPINNER), words("Checking the files."),
                "message.delta" to json("text" to JSONObject.NULL), toolStart("call-1", "terminal"), toolDone("call-1", "terminal"),
                "thinking.delta" to json("text" to RESTART_SPINNER), "thinking.delta" to json("text" to ""), words(after)))
            // The phone had not read Hermes' saved rows yet, or it had.
            for (phone in listOf(listOf(cutPrompt), saved)) {
                val s = cutByRestart(events, phone, saved, saved + hermesNote(1503, partialNote))
                assertEquals("'$after', phone rows ${phone.size}", listOf("user:1500", "assistant:1501", "tool:1502", "kept:$after", "assistant:1503"), drawn(s))
            }
        }
    }

    @Test(timeout = 10_000) fun keptWordsGiveWayToHermesSavedTurnByItsIdsAndPlaceNotItsWords() = runBlocking {
        // A reconnect reset while Codex still runs the turn: nothing saved yet. Hermes then saves the whole turn, with final words that differ from the streamed ones.
        val events = mappedFrames(listOf("message.start" to JSONObject(), words("Looking at the folder."), toolStart("exec-t1"), toolDone("exec-t1"), words("Done looking")))
        val savedTurn = listOf(cutPrompt, savedWords(1501, "Looking at the folder."), savedCall(1502, "", "exec-t1"), savedResult(1503, "exec-t1"),
            savedWords(1504, "Done looking at both files."))
        val s = cutByRestart(events, listOf(cutPrompt), listOf(cutPrompt), savedTurn)
        assertEquals(listOf("user:1500", "assistant:1501", "assistant:1502", "tool:1503", "assistant:1504"), drawn(s))
        assertEquals("no kept copy is left once Hermes holds the turn", emptyList<JSONObject>(), s.raw.rows("_unconfirmed_replies"))
    }

    @Test(timeout = 10_000) fun theOrderHoldsWhenTheSavedFrontOfTheTurnLeavesTheLiveViewMidStream() = runBlocking {
        // The phone reads Hermes' saved front (the first words with their step) while the turn runs; a later step and words stay unsaved until the restart.
        val events = mappedFrames(listOf("message.start" to JSONObject(), words("Checking the files."), toolStart("call-1", "terminal"), toolDone("call-1", "terminal"),
            words("The first is fine."), toolStart("exec-t2"), words("Now the second")))
        val front = listOf(cutPrompt, savedCall(1501, "Checking the files.", "call-1"), savedResult(1502, "call-1"))
        val s = cutByRestart(events, listOf(cutPrompt), front, front + hermesNote(1503, partialNote), midStream = 4 to front)
        assertEquals(listOf("user:1500", "assistant:1501", "tool:1502", "kept:The first is fine.", "card:exec-t2", "kept:Now the second", "assistant:1503"), drawn(s))
    }

    @Test(timeout = 10_000) fun aReplyThePhoneAlreadyHoldsIsDrawnOnceAtAnUnprovenEndAndAtAReset() = runBlocking {
        // Hermes' saved words equal the streamed ones, or differ (a final Hermes rewrote): its row is the reply's place in the turn either way.
        for (saved in listOf("All done.", "All done, both files are fine.")) {
            val events = mappedFrames(listOf("message.start" to JSONObject(), words("All done.")))
            val held = listOf(cutPrompt, savedWords(1501, saved))
            assertEquals("unproven end, '$saved'", listOf("user:1500", "assistant:1501"), drawn(unprovenEnd(events, held)))
            assertEquals("reset, '$saved'", listOf("user:1500", "assistant:1501"), drawn(cutByRestart(events, listOf(cutPrompt), held, midStream = events.lastIndex to held)))
        }
    }

    @Test(timeout = 10_000) fun wordsShownAgainForASavedSegmentDoNotMoveTheTurnOn() = runBlocking {
        // The phone already holds Hermes' saved first message when its frames arrive (a replay after a fresh attach, or commentary sent after its save):
        // the step's held call id puts those words back in their place, so the next words are the turn's second, not its third.
        val front = listOf(cutPrompt, savedCall(1501, "Checking the files.", "call-1"), savedResult(1502, "call-1"))
        val whole = front + savedWords(1503, "Both files are fine.")
        val events = mappedFrames(listOf("message.start" to JSONObject(), words("Checking the files."), "message.delta" to json("text" to JSONObject.NULL),
            toolStart("call-1", "terminal"), toolDone("call-1", "terminal"), words("Both files are fine")))
        // A reconnect cut the reply; Hermes then saved the rest of the turn.
        val reset = cutByRestart(events, front, front, whole)
        assertEquals(listOf("user:1500", "assistant:1501", "tool:1502", "assistant:1503"), drawn(reset))
        assertEquals("no kept copy is left once Hermes holds the turn", emptyList<JSONObject>(), reset.raw.rows("_unconfirmed_replies"))
        // An end Hermes' receipt did not prove, with the whole turn held by then.
        assertEquals(listOf("user:1500", "assistant:1501", "tool:1502", "assistant:1503"), drawn(unprovenEnd(events, whole, phoneRows = front)))
    }

    @Test(timeout = 10_000) fun aCutReplyWhosePromptThePhoneDoesNotHoldYetIsKeptUnboundNotRetiredByAnOlderTurn() = runBlocking {
        // This phone sent the question; the phone holds only the turn before it when the restart cuts the reply.
        val older = listOf(json("id" to 1400, "role" to "user", "content" to "Synthetic earlier question"), savedWords(1401, "Earlier answer."))
        val sent = QueuedMessage("Synthetic check the files", state = "sent", submissionId = "synthetic-send")
        val events = mappedFrames(listOf("message.start" to JSONObject(), words("Looking at the folder."), toolStart("exec-t1"), toolDone("exec-t1"), words("Now the files")))
        val s = cutByRestart(events, older, older + cutPrompt, older + cutPrompt + hermesNote(1501, notProcessed), ownSend = sent)
        assertTrue("not bound to the older turn: ${s.raw.rows("_unconfirmed_replies")}", s.raw.rows("_unconfirmed_replies").all { it.string("_history_user_row_id").isEmpty() })
        val shown = drawn(s)
        assertEquals(shown.toString(), listOf("kept:Looking at the folder.", "card:exec-t1", "kept:Now the files"), shown.filter { it.startsWith("kept:") || it.startsWith("card:") })
        assertTrue(shown.toString(), shown.containsAll(listOf("user:1400", "assistant:1401", "user:1500", "assistant:1501")))
    }

    @Test(timeout = 10_000) fun aCutReplyWithNoWordsKeepsNothing() = runBlocking {
        val events = mappedFrames(listOf("message.start" to JSONObject(), "thinking.delta" to json("text" to RESTART_SPINNER), toolStart("exec-t0"), toolDone("exec-t0")))
        val s = cutByRestart(events, listOf(cutPrompt), listOf(cutPrompt), listOf(cutPrompt, hermesNote(1501, notProcessed)))
        assertEquals(listOf("user:1500", "assistant:1501"), drawn(s))
    }

    /** TURN-121 D2: Hermes' thinking_callback frames (spinner faces, wait lines, an empty clear) rewrite the live status line; they are not reasoning. */
    @Test(timeout = 10_000) fun hermesThinkingFramesReachTheChatAsStatusNotReasoning() = runBlocking {
        val wire = Wire().apply { latest = 4 }
        wire.replay = JSONArray().put(wire.event(1, "message.start"))
            .put(wire.event(2, "thinking.delta", json("text" to "⏳ rate limited — resets in ~5m, retrying in 300s (attempt 1/3)")))
            .put(wire.event(3, "reasoning.delta", json("text" to "Planning")))
            .put(wire.event(4, "message.complete", json("status" to "completed")))
        val seen = mutableListOf<Pair<String, String>>()
        try {
            withTimeout(1500) { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { seen += it.type to it.payload().string("text") } }
            assertEquals(listOf("server_turn_started", "thinking", "reasoning", "done"), seen.map { it.first })
            assertEquals("⏳ rate limited — resets in ~5m, retrying in 300s (attempt 1/3)", seen[1].second)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun anOrdinaryTurnEndStreamsItsTokenThenDone() = runBlocking {
        val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString") args!![1] else error("Unexpected preference operation")
        } as android.content.SharedPreferences
        val store = AuthStore(object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(ignored: String, mode: Int) = prefs
        }, AppCipher())
        val wire = Wire(store = store).apply { latest = 3 }
        wire.replay = JSONArray().put(wire.event(1, "message.start")).put(wire.event(2, "message.delta", json("text" to "Synthetic final")))
            .put(wire.event(3, "message.complete", json("status" to "completed")))
        val types = mutableListOf<String>()
        try {
            withTimeout(3000) { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") { types += it.type } }
            assertEquals(listOf("server_turn_started", "token", "done"), types)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun anAckLostAfterSettlementCanUseTheRetainedNativeInputIdentity() = runBlocking {
        val wire = Wire().apply { loseAck = true }
        try {
            runCatching { NativeOperations(wire.client).call("/api/chat/start", emptyMap(), "POST",
                json("session_id" to "stored-a", "message" to "Synthetic input", "client_request_id" to "submission-a"), 1024 * 1024) }
            val prior = wire.pending
            wire.pending = JSONArray(); wire.running = false
            wire.receipts.getValue("admitted-a").put("status", "terminal").put("outcome", "completed")
            wire.replay = JSONArray().put(wire.event(1, "session.info", json("pending" to prior)))
            val receipt = NativeOperations(wire.client).call("/api/mobile/receipts/submission-a", mapOf("session_id" to "stored-a"), "GET", null, 1024 * 1024)
            assertEquals("terminal", receipt.string("status"))
            assertEquals("completed", receipt.string("outcome"))
            assertEquals(1, wire.calls.count { it.string("method") == "prompt.submit" })
        } finally { wire.client.closeNative() }
    }

    // R3-F5: Hermes folds a steer in only after a tool step. One that comes during a plain-text reply is left over when the turn ends; on a
    // classic-CLI surface (every chat Beacon creates: a canonical create without a source is 'cli', gateway/session_policy.build_policy) Hermes
    // moves it to the turn's receipt for the client to send as its own next turn (gateway/session_cli_turn.take_cli_result_fields).
    private fun steerReceipt(source: String?, leftover: Any?): JSONObject = runBlocking {
        val wire = Wire().apply { this.source = source }
        try {
            NativeOperations(wire.client).call("/api/chat/start", emptyMap(), "POST",
                json("session_id" to "stored-a", "message" to "Synthetic story", "client_request_id" to "submission-a"), 1024 * 1024)
            wire.receipts.getValue("admitted-a").put("status", "terminal").put("outcome", "completed")
                .put("result", json("final_response" to "Synthetic story", "pending_steer" to leftover))
            NativeOperations(wire.client).call("/api/mobile/receipts/submission-a",
                mapOf("session_id" to "stored-a", "admission_id" to "admitted-a", "completed_proof" to "1"), "GET", null, 1024 * 1024)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun aSteerHermesHandsBackComesWithTheFinishedTurnsReceipt() {
        val receipt = steerReceipt("cli", "Synthetic steer")
        assertEquals("The leftover steer reaches the phone", "Synthetic steer", receipt.opt("pending_steer"))
        assertFalse(receipt.has("result"))
        assertEquals("No leftover reads as an empty one", "", steerReceipt("cli", JSONObject.NULL).opt("pending_steer"))
    }

    @Test(timeout = 10_000) fun aSurfaceWhereHermesRunsTheLeftoverItselfSaysSo() {
        val receipt = steerReceipt("tui", "Synthetic steer")
        assertFalse("Hermes sends it as its own follow-up there", receipt.has("pending_steer"))
        assertTrue(receipt.optBoolean("steer_followup"))
        val unknown = steerReceipt(null, "Synthetic steer")
        assertFalse(unknown.has("pending_steer") || unknown.has("steer_followup"))
    }

    @Test(timeout = 10_000) fun aLiveEpochChangeDoesNotDiscardNewSequenceOne() = runBlocking {
        val wire = Wire(strictEpoch = false)
        wire.client.nativeSession("stored-a")
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val collector = launch { wire.client.nativeEvents { frame ->
            val p = frame.optJSONObject("params") ?: JSONObject()
            if (p.string("type") == "message.delta") {
                if (p.optJSONObject("payload")?.string("text") == "Before reset") first.complete(Unit) else second.complete(Unit)
            }
        } }
        try {
            withTimeout(1500) { wire.subscribed.await() }
            wire.emit(wire.event(41, "message.delta", json("text" to "Before reset")))
            withTimeout(1500) { first.await() }
            wire.epoch = "second-epoch"; wire.latest = 0
            wire.emit(wire.event(1, "message.delta", json("text" to "After reset")))
            assertNotNull("A reset sequence belongs to its new native epoch", withTimeoutOrNull(1500) { second.await() })
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    private suspend fun staleFramesCannotFollowFinalSnapshot(reset: Boolean, evictDuringGap: Boolean = false) = coroutineScope {
        val wire = Wire().apply { latest = 0 }
        wire.client.nativeSession("stored-a")
        val before = CompletableDeferred<Unit>()
        val barrier = CompletableDeferred<Unit>()
        val newer = CompletableDeferred<Unit>()
        val stale = mutableListOf<String>()
        var finalSnapshot = false
        var replayReset = false
        var snapshotEpoch = ""
        val collector = launch { wire.client.nativeEvents { frame ->
            val p = frame.optJSONObject("params") ?: JSONObject()
            val payload = p.optJSONObject("payload") ?: JSONObject()
            when {
                frame.string("method") == "reconciliation.required" -> {
                    val history = p.getJSONObject("history")
                    assertEquals(10L, history.optLong("last_sequence"))
                    assertFalse(history.optBoolean("running"))
                    assertEquals("Final snapshot", history.rows("messages").last().string("content"))
                    finalSnapshot = true
                    replayReset = p.optBoolean("replay_reset")
                    snapshotEpoch = p.string("epoch")
                }
                payload.string("text") == "Before snapshot" -> before.complete(Unit)
                payload.string("text") == "Unsequenced barrier" -> barrier.complete(Unit)
                payload.string("text") == "After watermark" -> newer.complete(Unit)
                finalSnapshot && p.string("type") in setOf("message.start", "tool.start", "message.delta") -> stale += p.string("type")
            }
        } }
        try {
            withTimeout(1500) { wire.subscribed.await() }
            wire.emit(wire.event(1, "message.delta", json("text" to "Before snapshot")))
            withTimeout(1500) { before.await() }
            wire.running = false; wire.latest = 10
            wire.history.put(json("id" to 2, "role" to "assistant", "content" to "Final snapshot"))
            if (reset) wire.epoch = "second-epoch"
            if (evictDuringGap) wire.afterHistoryRead = { wire.afterHistoryRead = null; wire.epoch = "second-epoch" }
            wire.emit(wire.event(3, "message.start"))
            if (!evictDuringGap) {
                wire.emit(wire.event(4, "tool.start", json("id" to "stale-tool", "name" to "synthetic_work")))
                wire.emit(wire.event(5, "message.delta", json("text" to "Old token")))
            }
            wire.emit(wire.event(0, "status.update", json("text" to "Unsequenced barrier")).apply { remove("seq"); remove("replay_epoch") })
            withTimeout(1500) { barrier.await() }
            assertTrue("The controlled final snapshot was delivered", finalSnapshot)
            assertTrue("Final history must not be followed by stale start/tool/token frames: $stale", stale.isEmpty())
            if (evictDuringGap) {
                assertTrue("The newly discovered epoch must reset the live projection", replayReset)
                assertEquals(wire.epoch, snapshotEpoch)
            }
            wire.emit(wire.event(11, "message.delta", json("text" to "After watermark")))
            withTimeout(1500) { newer.await() }
            assertEquals("Later frames must not require another history read", 1, wire.historyReads)
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun aSameEpochFinalSnapshotWatermarkSuppressesOlderGapFrames() = runBlocking {
        staleFramesCannotFollowFinalSnapshot(reset = false)
    }

    @Test(timeout = 10_000) fun aNewEpochFinalSnapshotWatermarkSuppressesOlderResetFrames() = runBlocking {
        staleFramesCannotFollowFinalSnapshot(reset = true)
    }

    @Test(timeout = 10_000) fun aGapSnapshotAdoptsAnEpochChangedDuringReconciliation() = runBlocking {
        staleFramesCannotFollowFinalSnapshot(reset = false, evictDuringGap = true)
    }

    @Test(timeout = 10_000) fun reconnectingWithAnOldEpochUsesTheCurrentFinalSnapshot() = runBlocking {
        val wire = Wire().apply {
            running = false
            history.put(json("id" to 2, "role" to "assistant", "content" to "Synthetic final reply"))
        }
        val snapshot = CompletableDeferred<JSONObject>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "stored-a:old-epoch:41") {
            if (it.type == "state_saved") snapshot.complete(it.payload())
        } }
        try {
            val payload = withTimeout(1500) { snapshot.await() }
            assertTrue(payload.optBoolean("replay_reset"))
            val session = payload.getJSONObject("session")
            assertEquals("Synthetic final reply", session.rows("messages").last().string("content"))
            assertEquals("", session.string("active_stream_id"))
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun firstAttachmentToATruncatedRingStillReconcilesItsSnapshot() = runBlocking {
        val wire = Wire().apply { truncatedAtZero = true }
        val snapshot = CompletableDeferred<JSONObject>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") {
            if (it.type == "state_saved") snapshot.complete(it.payload())
        } }
        try {
            val payload = withTimeoutOrNull(1500) { snapshot.await() }
            assertEquals("Truncation at initial attachment cannot be silently skipped", "stored-a", payload?.optJSONObject("session")?.string("active_stream_id"))
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun aFinalCommittedBetweenRestHistoryAndNativeSnapshotIsNotLost() = runBlocking {
        val wire = Wire().apply { truncatedAtZero = true }
        wire.afterHistoryRead = {
            wire.afterHistoryRead = null
            wire.history.put(json("id" to 2, "role" to "assistant", "content" to "Final committed during refresh"))
            wire.running = false; wire.latest = 4
        }
        val snapshot = CompletableDeferred<JSONObject>()
        val collector = launch { NativeOperations(wire.client).stream("/api/chat/stream", mapOf("session_id" to "stored-a"), "") {
            if (it.type == "state_saved") snapshot.complete(it.payload())
        } }
        try {
            val payload = withTimeout(1500) { snapshot.await() }
            assertEquals("Final committed during refresh", payload.getJSONObject("session").rows("messages").last().string("content"))
        } finally { collector.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun identicalWordsDoNotMatchAnotherSubmissionReceipt() = runBlocking {
        val wire = Wire()
        wire.pending = JSONArray().put(json("input_id" to "different-submission", "admission_id" to "someone-else",
            "text" to "Repeatable words", "status" to "queued", "ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile")))
        try {
            val receipt = runCatching { NativeOperations(wire.client).call("/api/mobile/receipts/submission-a", mapOf("session_id" to "stored-a"), "GET", null, 1024 * 1024) }.getOrNull()
            assertEquals("A missing identity remains unknown even when message text matches", "unknown", receipt?.string("status"))
            assertEquals(0, wire.calls.count { it.string("method") == "prompt.submit" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun repeatedIntendedWordsKeepTheirSeparateSubmissionIdentities() = runBlocking {
        val wire = Wire()
        try {
            for (id in listOf("intended-a", "intended-b")) NativeOperations(wire.client).call("/api/chat/start", emptyMap(), "POST",
                json("session_id" to "stored-a", "message" to "Same intended words", "client_request_id" to id), 1024 * 1024)
            assertEquals(listOf("intended-a", "intended-b"), wire.calls.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params").string("submission_id") })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 10_000) fun aQueuedAdmissionWithoutAUserRowStillConfirmsAdmission() = runBlocking {
        val wire = Wire()
        try {
            val receipt = NativeOperations(wire.client).call("/api/chat/start", emptyMap(), "POST",
                json("session_id" to "stored-a", "message" to "Synthetic input", "client_request_id" to "submission-b"), 1024 * 1024)
            assertTrue(receipt.optBoolean("accepted"))
            assertEquals("queued", receipt.string("status"))
            assertFalse(receipt.has("user_row_id"))
            assertEquals("submission-b", wire.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params").string("submission_id"))
        } finally { wire.client.closeNative() }
    }
}
