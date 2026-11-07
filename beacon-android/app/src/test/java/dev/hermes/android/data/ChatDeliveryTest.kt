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
class ChatDeliveryTest {
    private class Wire(val canonical: Boolean = true, val strictEpoch: Boolean = true) : WebSocket.Factory {
        val calls = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val listeners = java.util.concurrent.CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        var epoch = "first-epoch"
        var running = true
        var loseAck = false
        var pending = JSONArray()
        var replay = JSONArray()
        var latest = 3L
        var truncatedAtZero = false
        var historyReads = 0
        var history = JSONArray().put(json("id" to 1, "role" to "user", "content" to "Synthetic task"))
        var afterHistoryRead: (() -> Unit)? = null
        val receipts = mutableMapOf<String, JSONObject>()
        val subscribed = CompletableDeferred<Unit>()
        val client = BeaconClient(AuthContext("https://delivery.example", emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
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
                            "info" to json("profile_name" to "default", "profile_id" to "synthetic-profile"))
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
        assertEquals("Turn status unknown", after?.status)
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
