package dev.hermes.android.data

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

class NativeOperationsTest {
    /** A Hermes whose chat has no agent until `approval.pending` starts it, like a lazily resumed runtime. */
    internal class Fixture(val refuseBuild: Boolean = false, val canonical: Boolean = false) : WebSocket.Factory {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val httpRequests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        private lateinit var listener: WebSocketListener
        private lateinit var socket: WebSocket
        var agentBuilt = false
        var builtAtWrite: Boolean? = null
        var model = "old-model"
        var profileModel = "old-model"
        var yolo = true
        var running = true
        var refuseRuntimeResume = false
        var messages = JSONArray()
        var revision: Any = 4
        var generation: Any = 7
        var reasoning = "medium"
        var pending = JSONArray()
        var prompts = JSONArray()
        /** What a gateway without session authority answers to `approval.pending` (its open approvals) and lists as `open_requests` on resume. */
        var legacyApprovals = JSONArray()
        var openRequests = JSONArray()
        var snapshotOverride: JSONObject? = null
        var modelInventoryOverride: JSONObject? = null
        var mutationOverride: JSONObject? = null
        var disconnectMutation = false
        var responseOverride: JSONObject? = null
        var steerResult = json("status" to "queued")
        var steerErrorCode: Int? = null
        /** What Hermes answers to file.attach instead of staging the file: (code, message), as gateway/session_controls.py writes them. */
        var fileAttachError: Pair<Int, String>? = null
        /** A test's own Hermes for one method: its answer is the result, or `{"error": {...}}` for a refusal; null leaves the method to the fixture. */
        var onRpc: ((String, JSONObject) -> JSONObject?)? = null
        /** The Beacon plugin's relay that takes file frames in acknowledged pieces (form 3, CHAT-121 follow-up 8) is in front of Hermes; the bytes it took. */
        var relayPieces = false
        val pieceBytes = java.util.concurrent.atomic.AtomicLong()
        /** The sockets of one file each (form 4) the phone opened: its file frames in pieces go on them, never on the chat's. */
        val fileSockets = java.util.concurrent.atomic.AtomicInteger()
        private fun reply(id: String, result: JSONObject) = json("jsonrpc" to "2.0", "id" to id, "result" to result).toString()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain ->
                httpRequests += chain.request()
                val path = chain.request().url.encodedPath
                val found = (path != CANONICAL_TICKET_PATH || relayPieces) && !path.endsWith("/voice-history")
                // A file's own socket (form 4, follow-up 9) asks its ticket with {"purpose": "file"}, and the plugin's answer says so.
                val file = path == CANONICAL_TICKET_PATH && chain.request().body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty().contains("\"purpose\":\"file\"")
                if (file) fileSockets.incrementAndGet()
                val body = when {
                    path == CANONICAL_TICKET_PATH && relayPieces -> json("ticket" to "fixture-relay-ticket", "path" to "/api/plugins/beacon/gateway/ws", "profile" to "default",
                        "canonical" to true, "expires_in" to 25, "attach_frame_limit" to 384L * 1024 * 1024, "attach_pieces" to ATTACHMENT_PIECES_FORM,
                        "attach_file_limit" to 256L * 1024 * 1024, "attach_image_limit" to 25L * 1024 * 1024).apply { if (file) put("purpose", "file") }
                    path == "/api/auth/ws-ticket" -> json("ticket" to "fixture-ticket")
                    path == "/api/model/options" -> modelInventoryOverride?.copy() ?: json("model" to profileModel, "provider" to "fixture", "providers" to JSONArray())
                    path == "/api/sessions/stored-a" -> json("id" to "stored-a", "profile" to "default", "title" to "Fixture", "message_count" to 0)
                    path == "/api/sessions/branch-a" -> json("id" to "branch-a", "profile" to "default", "title" to "Branch", "message_count" to 0)
                    path == "/api/sessions/stored-a/messages" -> json("session_id" to "stored-a", "profile" to "default", "messages" to JSONArray(), "pagination" to json("returned" to 0))
                    else -> json("detail" to "Not Found")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (found) 200 else 404).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                private val body = okio.Buffer()
                private var header: JSONObject? = null
                private var held = 0L
                // As the plugin's relay (form 3): the frame is joined from a header and the base64, each piece acknowledged, and Hermes gets it whole as text.
                override fun send(bytes: ByteString): Boolean {
                    if (!relayPieces) return false
                    pieceBytes.addAndGet(bytes.size.toLong())
                    when (bytes[0]) {
                        PIECE_HEAD -> { header = JSONObject(bytes.substring(1).utf8()); body.clear(); held = 0 }
                        PIECE_MORE, PIECE_LAST -> {
                            val head = header ?: return false
                            body.write(bytes.substring(1)); held += bytes.size - 1
                            val id = JSONObject(head.getString("head") + head.getString("tail")).getString("id")
                            listener.onMessage(this, okio.Buffer().writeByte(PIECE_ACK.toInt()).writeLong(held).writeUtf8(id).readByteString())
                            if (bytes[0] == PIECE_LAST) { header = null; return send(head.getString("head") + body.readUtf8() + head.getString("tail")) }
                        }
                        else -> return false
                    }
                    return true
                }
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    calls += frame.copy()
                    val method = frame.getString("method")
                    methods += method
                    val params = frame.getJSONObject("params")
                    // Closed owner schemas: gateway/session_{mutations,tui_config,busy_controls}.py at bf0203a7.
                    val legacyOnly = method in setOf("session.title", "session.set_hidden", "session.archive", "session.branch", "session.delete", "session.close", "session.compress", "session.undo", "session.usage", "session.cwd.set", "image.attach", "image.detach")
                    val invalidSet = method == "config.set" && (params.string("key") == "model" ||
                        params.keys().asSequence().any { it !in setOf("session_id", "profile", "key", "value") + (if (params.string("key") == "reasoning") setOf("scope") else emptySet()) })
                    val invalidMutation = method == "session.mutate" && params.keys().asSequence().toSet() !=
                        setOf("session_id", "profile", "request_id", "expected_revision", "expected_generation", "operation", "payload")
                    val invalidResponse = method in setOf("approval.respond", "clarify.respond") && params.keys().asSequence().toSet() !=
                        setOf("session_id", "profile", "execution_generation", "prompt_id", if (method == "approval.respond") "choice" else "answer")
                    if (canonical && (legacyOnly || invalidSet || invalidMutation || invalidResponse)) {
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 4001, "message" to "invalid_params")).toString())
                        return true
                    }
                    if (method == "session.mutate" && disconnectMutation) {
                        listener.onFailure(this, java.io.IOException("Synthetic disconnect after mutation delivery"), null)
                        return true
                    }
                    if (method == "session.resume" && refuseRuntimeResume && params.string("session_id") == "runtime-a") {
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to if (canonical) 4001 else 4007, "message" to if (canonical) "not_found" else "session not found")).toString())
                        return true
                    }
                    if (method == "file.attach") {
                        val refused = fileAttachError
                        val answer = if (refused != null) json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to refused.first, "message" to refused.second))
                        else {
                            // gateway/session_ancillary.attach_file: the file is staged under attachments/ and its @file: reference comes back, quoted when the name has spaces.
                            val name = params.string("name")
                            val ref = "attachments/$name".let { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
                            json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to json("attached" to true, "name" to name, "path" to "/synthetic/attachments/$name",
                                "ref_path" to "attachments/$name", "ref_text" to "@file:$ref", "uploaded" to true))
                        }
                        listener.onMessage(this, answer.toString())
                        return true
                    }
                    if (method == "session.steer" && steerErrorCode != null) {
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to steerErrorCode, "message" to "Synthetic steer refusal")).toString())
                        return true
                    }
                    onRpc?.invoke(method, params)?.let { answer ->
                        listener.onMessage(this, (if (answer.has("error")) json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to answer.getJSONObject("error")).toString()
                            else reply(frame.getString("id"), answer)))
                        return true
                    }
                    val result = when (method) {
                        "session.resume" -> snapshotOverride?.copy() ?: json("session_id" to if (canonical) "stored-a" else "runtime-a", "session_key" to "stored-a", "stored_session_id" to "stored-a", "message_count" to messages.length(), "messages" to JSONArray(messages.toString()),
                            // Hermes reports the chat's YOLO state only from a built agent.
                            "info" to json("model" to model, "provider" to "fixture", "lazy" to !agentBuilt).apply {
                                if (canonical) put("profile_name", "default").put("profile_id", "synthetic-profile")
                                if (agentBuilt) put("yolo", yolo)
                            }).apply {
                                if (canonical) put("revision", revision).put("execution_generation", generation).put("running", running).put("pending", pending).put("prompts", prompts)
                                else if (openRequests.length() > 0) put("open_requests", openRequests)
                            }
                        "approval.pending" -> {
                            if (refuseBuild) {
                                listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 5032, "message" to "Fixture builder refused")).toString())
                                return true
                            }
                            agentBuilt = true
                            json("approvals" to legacyApprovals)
                        }
                        "config.set" -> {
                            builtAtWrite = agentBuilt
                            if (params.getString("key") == "yolo") {
                                yolo = params.getString("value") == "1"
                                json("key" to "yolo", "value" to params.getString("value"))
                            } else if (params.getString("key") == "fast") {
                                json("key" to "fast", "value" to params.getString("value"))
                            } else if (params.getString("key") == "reasoning") {
                                reasoning = params.getString("value")
                                json("key" to "reasoning", "value" to reasoning)
                            } else {
                                model = params.getString("value").substringBefore(" --provider ")
                                json("key" to "model", "value" to model, "confirm_required" to false)
                            }
                        }
                        "config.get" -> {
                            if (canonical && params.string("key") == "provider") {
                                listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 4001, "message" to "invalid_params")).toString())
                                return true
                            }
                            json("key" to params.getString("key"), "value" to if (params.string("key") == "reasoning") reasoning else "smart")
                        }
                        "model.options" -> json("model" to model, "provider" to "fixture", "providers" to JSONArray())
                        "session.title" -> json("session_id" to "runtime-a", "title" to params.string("title"))
                        "session.mutate" -> (mutationOverride?.copy() ?: json("session_id" to "stored-a", "operation" to params.string("operation"),
                            "revision" to 5, "execution_generation" to 7).apply {
                                params.optJSONObject("payload")?.let { payload -> payload.keys().forEach { key -> put(key, payload.opt(key)) } }
                                if (params.string("operation") == "delete") put("deleted_ids", JSONArray().put("stored-a"))
                                if (params.string("operation") == "compress") put("target_session_id", "stored-a").put("message_count", 2)
                                if (params.string("operation") == "branch") put("branched_session_id", "branch-a")
                                if (params.string("operation") == "rewind") put("rewound_count", 2).put("target_message", json("id" to params.getJSONObject("payload").getLong("target_message_id"), "role" to "user"))
                            }).also { result -> if (params.string("operation") == "model" && result.string("status").isBlank()) model = result.string("model") }
                        "session.stats" -> json("input_tokens" to 12, "output_tokens" to 3)
                        "session.interrupt" -> json("ref" to json("session_id" to "stored-a", "profile_id" to "synthetic-profile"), "execution_generation" to 7)
                        "approval.respond", "clarify.respond" -> responseOverride?.copy() ?: json("status" to "resolved", "prompt_id" to params.string("prompt_id"))
                        "session.events.since" -> json("epoch" to "fixture", "latest_seq" to 0, "events" to JSONArray())
                        "session.steer" -> steerResult.copy()
                        "image.attach_bytes" -> json("attached" to true, "path" to "/synthetic/image.png", "mime" to "image/png")
                        "prompt.submit" -> if (canonical) json("status" to "started", "admission_id" to "admission-a", "ref" to json("session_id" to "stored-a"))
                            else json("status" to "streaming", "user_row_id" to 42)
                        else -> JSONObject()
                    }
                    listener.onMessage(this, reply(frame.getString("id"), result))
                    return true
                }
            }
            this.listener = listener; this.socket = socket
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to canonical))).toString())
            return socket
        }
        fun emitToken(text: String, type: String = "token") = listener.onMessage(socket, json("method" to "event", "params" to json("session_id" to "runtime-a", "seq" to 1, "type" to type, "payload" to json("text" to text))).toString())
        /** One canonical event frame as Hermes' session_events.publish writes it (method/params/type/session_id/payload/replay_epoch/seq). */
        fun emitEvent(session: String, seq: Int, type: String, payload: JSONObject) = listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event",
            "params" to json("session_id" to session, "seq" to seq, "type" to type, "payload" to payload, "replay_epoch" to "fixture")).toString())
    }

    private suspend fun pick(fixture: Fixture) =
        fixture.client.post("/api/session/update", json("session_id" to "stored-a", "model" to "picked-model", "model_provider" to "fixture")).getJSONObject("session")

    @Test fun gatewayReadinessDoesNotCountAsTurnProgress() = runBlocking {
        val fixture = Fixture()
        val events = java.util.concurrent.CopyOnWriteArrayList<SseEvent>()
        val token = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            fixture.client.nativeStream("/api/chat/stream", mapOf("session_id" to "stored-a")) { event ->
                events += event
                if (event.payload().string("text") == "Real turn progress") token.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            fixture.emitToken("Real turn progress", "message.delta")
            withTimeout(5_000) { token.await() }
            assertEquals("Connection admission must not reset a quiet turn's backoff", 1, events.size)
            assertEquals("token", events.single().type)
            assertEquals("Real turn progress", events.single().payload().string("text"))
            assertTrue(collector.isActive)
        } finally { collector.cancelAndJoin(); fixture.client.closeNative() }
    }

    // A pick that names a provider, on a chat whose agent was still starting, showed "updated" and was then lost.
    @Test fun modelPickWaitsForTheChatsAgentBeforeItIsWritten() = runBlocking {
        val fixture = Fixture()
        try {
            val session = pick(fixture)
            assertEquals(listOf("session.resume", "approval.pending", "config.set", "session.resume"), fixture.methods.filter { it != "client.capabilities" })
            assertEquals(true, fixture.builtAtWrite)
            assertEquals("picked-model", session.getString("model"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun steerUsesTheBoundRuntimeWithoutReplacingTheLiveCollector() = runBlocking {
        val fixture = Fixture()
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.client.nativeReplay(beforePrompt = true)
            val token = CompletableDeferred<String>()
            val collector = launch(Dispatchers.IO) { fixture.client.nativeEvents { frame ->
                val params = frame.optJSONObject("params") ?: return@nativeEvents
                if (params.string("type") == "token") token.complete(params.getJSONObject("payload").string("text"))
            } }
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            val response = fixture.client.post("/api/chat/steer", json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Make it concise"))
            assertEquals("queued", response.string("status"))
            val sent = fixture.calls.single { it.string("method") == "session.steer" }.getJSONObject("params")
            assertEquals("runtime-a", sent.string("session_id"))
            assertEquals("default", sent.string("profile"))
            assertEquals("Make it concise", sent.string("text"))
            assertFalse(sent.has("execution_generation"))
            fixture.emitToken("still streaming")
            assertEquals("still streaming", withTimeout(5_000) { token.await() })
            assertTrue(collector.isActive)
            collector.cancelAndJoin()
        } finally { fixture.client.closeNative() }
    }

    @Test fun aRefusedSteerDoesNotCloseTheLiveCollector() = runBlocking {
        val fixture = Fixture()
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.client.nativeReplay(beforePrompt = true)
            val token = CompletableDeferred<String>()
            val collector = launch(Dispatchers.IO) { fixture.client.nativeEvents { frame ->
                val params = frame.optJSONObject("params") ?: return@nativeEvents
                if (params.string("type") == "token") token.complete(params.getJSONObject("payload").string("text"))
            } }
            withTimeout(5_000) { while (fixture.calls.count { it.string("method") == "session.events.since" } < 2) delay(10) }
            fixture.steerErrorCode = 4010
            val failure = runCatching { fixture.client.post("/api/chat/steer", json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Correction")) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertEquals(1, fixture.calls.count { it.string("method") == "session.steer" })
            fixture.emitToken("still streaming after refusal")
            assertEquals("still streaming after refusal", withTimeout(5_000) { token.await() })
            assertTrue(collector.isActive)
            collector.cancelAndJoin()
        } finally { fixture.client.closeNative() }
    }

    @Test fun canonicalSubmitCarriesTheSavedIdentityModelAndImageOnTheAdvertisedAuthority() = runBlocking {
        val fixture = Fixture(canonical = true)
        try {
            val attachment = json("id" to "synthetic", "name" to "sample.png", "mime" to "image/png", "staged_base64" to "c2FtcGxl",
                "scope_key" to fixture.client.scope.session("stored-a").key)
            val result = fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Describe this", "client_request_id" to "local-123",
                "attachments" to JSONArray().put(attachment), "queued_model_snapshot" to true, "model" to "snapshot-model", "model_provider" to "fixture"))
            assertEquals("admission-a", result.string("admission_id"))
            val sent = fixture.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("local-123", sent.string("submission_id"))
            assertTrue(sent.optBoolean("queued"))
            assertEquals("stored-a", sent.string("session_id"))
            assertEquals("snapshot-model", sent.getJSONObject("model_override").string("model"))
            assertEquals("fixture", sent.getJSONObject("model_override").string("provider"))
            assertEquals("/synthetic/image.png", sent.getJSONArray("attachments").getJSONObject(0).string("path"))
            assertEquals("image/png", sent.getJSONArray("attachments").getJSONObject(0).string("mime"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun canonicalRejectionWithoutDeliveredFalseIsNotSafeToQueue() = runBlocking {
        val fixture = Fixture(canonical = true)
        try {
            val binding = fixture.client.nativeSession("stored-a")
            fixture.steerResult = json("status" to "rejected", "execution_generation" to 7, "authority_epoch" to 1)
            val body = json("session_id" to "stored-a", "runtime_session_id" to binding.runtimeSessionId, "text" to "Correction")
            assertFalse(fixture.client.post("/api/chat/steer", body).optBoolean("safe_to_queue"))
            fixture.steerResult = fixture.steerResult.copy().put("delivered", false)
            assertTrue(fixture.client.post("/api/chat/steer", body).optBoolean("safe_to_queue"))
            assertTrue(fixture.calls.filter { it.string("method") == "session.steer" }.all { it.getJSONObject("params").optInt("execution_generation") == 7 })
        } finally { fixture.client.closeNative() }
    }

    /**
     * CHAT-121 follow-up 3: a chat with session authority refused every file that is not one of four image types ("This Hermes can't take this
     * attachment"), before asking Hermes, although Hermes' own Desktop stages any file there with file.attach (gateway/session_ancillary.py, 5551a2c342).
     * A PDF, an Office file and a file with a non-Latin name now go in one message, each as Hermes' own @file: reference in the text.
     */
    @Test fun aChatWithSessionAuthoritySendsDocumentsAsHermesFileReferences() = runBlocking {
        // CHAT-121 follow-ups 8 and 9: through the Beacon plugin's relay (form 4), the same small files go in acknowledged pieces, each on a socket of its own,
        // and Hermes gets the same frames.
        for (relay in listOf(false, true)) {
        val fixture = Fixture(canonical = true).apply { relayPieces = relay }
        try {
            val key = fixture.client.scope.session("stored-a").key
            val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            val files = listOf(
                json("id" to "doc-1", "name" to "Synthetic summary 2026.pdf", "mime" to "application/pdf", "staged_base64" to "JVBERi0xLjQgc3ludGhldGlj", "scope_key" to key),
                json("id" to "doc-2", "name" to "Team plan (draft).docx", "mime" to docx, "staged_base64" to "UEsDBHN5bnRoZXRpYw==", "scope_key" to key),
                json("id" to "doc-3", "name" to "ملف تجريبي.txt", "mime" to "text/plain", "staged_base64" to "c3ludGhldGlj", "scope_key" to key),
                json("id" to "img-1", "name" to "sample.png", "mime" to "image/png", "staged_base64" to "c2FtcGxl", "scope_key" to key))
            val result = fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read these", "client_request_id" to "local-docs",
                "attachments" to JSONArray(files)))
            assertEquals("admission-a", result.string("admission_id"))
            val staged = fixture.calls.filter { it.string("method") == "file.attach" }.map { it.getJSONObject("params") }
            assertEquals(listOf("Synthetic summary 2026.pdf", "Team plan (draft).docx", "ملف تجريبي.txt"), staged.map { it.string("name") })
            assertEquals(listOf("data:application/pdf;base64,JVBERi0xLjQgc3ludGhldGlj", "data:$docx;base64,UEsDBHN5bnRoZXRpYw==", "data:text/plain;base64,c3ludGhldGlj"),
                staged.map { it.string("data_url") })
            assertTrue(staged.all { it.string("session_id") == "stored-a" && it.string("profile") == "default" })
            val sent = fixture.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("Read these\n@file:\"attachments/Synthetic summary 2026.pdf\"\n@file:\"attachments/Team plan (draft).docx\"\n@file:\"attachments/ملف تجريبي.txt\"",
                sent.string("text"))
            // The image still goes as an image; only it is in attachments.
            assertEquals(listOf("/synthetic/image.png"), (0 until sent.getJSONArray("attachments").length()).map { sent.getJSONArray("attachments").getJSONObject(it).string("path") })
            assertEquals(4, result.getJSONArray("attachment_receipts").length())
            assertEquals("$relay", relay, fixture.pieceBytes.get() > 0)
            // Follow-up 9: each file in pieces goes on a socket of its own (the three documents and the image), never on the chat's.
            assertEquals("$relay", if (relay) 4 else 0, fixture.fileSockets.get())
        } finally { fixture.client.closeNative() }
        }
    }

    /** Only a Hermes that really cannot take the file gets the refusal line, and it names the file; nothing is submitted and the draft stays (NativeTurnNotSent). */
    @Test fun aFileThisHermesCannotTakeIsNamedAndNothingIsSent() = runBlocking {
        val key = { fixture: Fixture -> fixture.client.scope.session("stored-a").key }
        fun files(fixture: Fixture, big: Boolean = false) = JSONArray(listOf(
            json("id" to "doc-1", "name" to "Synthetic summary 2026.pdf", "mime" to "application/pdf", "staged_base64" to "JVBERi0xLjQgc3ludGhldGlj", "scope_key" to key(fixture)),
            json("id" to "doc-2", "name" to "ملف تجريبي.docx", "mime" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "staged_base64" to if (big) "A".repeat(CANONICAL_RELAY_FRAME_BYTES) else "UEsDBHN5bnRoZXRpYw==", "scope_key" to key(fixture))))
        // A Hermes without file.attach on the chat's authority (-32601), its own refusal of the file (invalid_params), a Hermes before 5551a2c342 (session not found).
        for (refusal in listOf(-32601 to "unknown method: file.attach", 4001 to "invalid_params", 4001 to "session not found")) {
            val fixture = Fixture(canonical = true).apply { fileAttachError = refusal }
            try {
                val failure = runCatching { fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read these", "client_request_id" to "local-r",
                    "attachments" to files(fixture))) }.exceptionOrNull()
                assertTrue("$refusal: $failure", failure is AttachmentRefused && failure.file == "Synthetic summary 2026.pdf" && failure.line == ATTACHMENT_REFUSED_LINE)
                assertEquals("This Hermes can't take “Synthetic summary 2026.pdf”. The message stays on this phone.", failure?.message)
                assertTrue(fixture.calls.none { it.string("method") == "prompt.submit" })
            } finally { fixture.client.closeNative() }
        }
        // Not about the file: no refusal line, the usual "not sent".
        val denied = Fixture(canonical = true).apply { fileAttachError = 4001 to "permission_denied" }
        try {
            val failure = runCatching { denied.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read these", "client_request_id" to "local-p",
                "attachments" to files(denied))) }.exceptionOrNull()
            assertTrue("$failure", failure is NativeTurnNotSent && failure !is AttachmentRefused)
            assertTrue(denied.calls.none { it.string("method") == "prompt.submit" })
        } finally { denied.client.closeNative() }
        // Too large for one frame of the plugin relay (Hermes serves no staged upload to Beacon): named before anything is staged, even when it is not the
        // first file, and a photo too.
        val large = Fixture(canonical = true)
        try {
            val failure = runCatching { large.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read these", "client_request_id" to "local-l",
                "attachments" to files(large, big = true))) }.exceptionOrNull()
            assertTrue("$failure", failure is AttachmentRefused && failure.file == "ملف تجريبي.docx" && failure.line == ATTACHMENT_TOO_LARGE_LINE)
            assertTrue(large.calls.none { it.string("method") in setOf("file.attach", "image.attach_bytes", "prompt.submit") })
        } finally { large.client.closeNative() }
        val photo = Fixture(canonical = true)
        try {
            val failure = runCatching { photo.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Look", "client_request_id" to "local-i",
                "attachments" to JSONArray(listOf(json("id" to "img-1", "name" to "large photo.jpg", "mime" to "image/jpeg", "staged_base64" to "A".repeat(CANONICAL_RELAY_FRAME_BYTES),
                    "scope_key" to key(photo)))))) }.exceptionOrNull()
            assertTrue("$failure", failure is AttachmentRefused && failure.file == "large photo.jpg" && failure.line == ATTACHMENT_TOO_LARGE_LINE)
            assertTrue(photo.calls.none { it.string("method") in setOf("image.attach_bytes", "prompt.submit") })
        } finally { photo.client.closeNative() }
    }

    /** The frame check counts what Android's JSON writes: each "/" of base64 escaped as "\/", and a name up to 6 bytes a character. */
    @Test fun theFrameCheckLeavesRoomForEscapesAndTheEnvelope() {
        val slashes = "/".repeat(4_000_000)
        assertFalse("4 MB of slashes is 8 MB once escaped", fitsCanonicalFrame(slashes + "A".repeat(400_000), "a.pdf", "application/pdf"))
        assertTrue(fitsCanonicalFrame("A".repeat(CANONICAL_RELAY_FRAME_BYTES - 2048), "a.pdf", "application/pdf"))
        assertFalse(fitsCanonicalFrame("A".repeat(CANONICAL_RELAY_FRAME_BYTES - 2048), "ملف".repeat(200), "application/pdf"))
    }

    /**
     * CHAT-121 follow-up 4: a file kept on the phone (past one text frame) goes only to a plugin that takes it in pieces. On a connection without
     * them (a 1.2.0 plugin, or no plugin) it is named with that connection's real limit, before anything is sent; a kept copy that is gone is not sent.
     */
    @Test fun aKeptFileOnAConnectionWithoutPiecesIsNamedWithItsRealLimit() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("chat-attachments").toFile()
        ChatAttachmentStore.dir = dir
        val id = java.util.UUID.randomUUID().toString()
        java.io.File(dir, id).writeBytes(ByteArray(7 * 1024 * 1024))
        val fixture = Fixture(canonical = true)
        try {
            val item = json("id" to id, "state" to "ready", "name" to "Synthetic report 2026.pdf", "mime" to "application/pdf", "size" to 7 * 1024 * 1024,
                "staged_file" to true, "scope_key" to fixture.client.scope.session("stored-a").key)
            suspend fun send() = runCatching { fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read this", "client_request_id" to "local-k",
                "attachments" to JSONArray().put(item))) }.exceptionOrNull()
            val failure = send()
            assertTrue("$failure", failure is AttachmentRefused && failure.line == ATTACHMENT_TOO_LARGE_LINE && failure.limitMb == "6")
            assertEquals("“Synthetic report 2026.pdf” is too large to send to this Hermes (6 MB at most). The message stays on this phone.", failure?.message)
            assertTrue(fixture.calls.none { it.string("method") in setOf("file.attach", "image.attach_bytes", "prompt.submit") })
            java.io.File(dir, id).delete()
            val gone = send()
            assertTrue("$gone", gone is NativeTurnNotSent && gone !is AttachmentRefused)
            assertTrue(fixture.calls.none { it.string("method") in setOf("file.attach", "prompt.submit") })
        } finally { fixture.client.closeNative(); ChatAttachmentStore.dir = null; dir.deleteRecursively() }
    }

    @Test fun legacyModelMismatchAndAFileTheCanonicalHermesCannotTakeAreRefusedBeforePrompt() = runBlocking {
        val legacy = Fixture()
        try {
            val failure = runCatching { legacy.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Queued text",
                "client_request_id" to "local-a", "queued_model_snapshot" to true, "model" to "earlier-model", "model_provider" to "fixture")) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertTrue(legacy.calls.none { it.string("method") in setOf("prompt.submit", "config.set") })
        } finally { legacy.client.closeNative() }
        // A Hermes whose chat authority has no file.attach (before 5551a2c342 it answered from the legacy sidecar: "session not found").
        val canonical = Fixture(canonical = true).apply { fileAttachError = 4001 to "session not found" }
        try {
            val file = json("id" to "file-a", "name" to "notes.txt", "mime" to "text/plain", "staged_base64" to "c2FtcGxl",
                "scope_key" to canonical.client.scope.session("stored-a").key)
            val failure = runCatching { canonical.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Read this",
                "client_request_id" to "local-b", "attachments" to JSONArray().put(file))) }.exceptionOrNull()
            assertTrue(failure is NativeTurnNotSent)
            assertTrue(canonical.calls.none { it.string("method") == "prompt.submit" })
        } finally { canonical.client.closeNative() }
    }

    @Test fun aLegacyPhoneQueueRemainsQueuedIfTheIdleCheckRacesAnotherTurn() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Next turn", "client_request_id" to "local-a",
                "queued_model_snapshot" to true, "model" to "old-model", "model_provider" to "fixture"))
            val sent = fixture.calls.single { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertTrue(sent.optBoolean("queued"))
            assertFalse(sent.has("submission_id"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun modelPickStillReachesHermesWhenTheAgentBuildFailed() = runBlocking {
        val fixture = Fixture(refuseBuild = true)
        try {
            val session = pick(fixture)
            assertEquals(false, fixture.builtAtWrite)
            assertEquals("picked-model", session.getString("model"))
        } finally { fixture.client.closeNative() }
    }

    private suspend fun yoloOff(fixture: Fixture) =
        fixture.client.post("/api/approval/mode", json("session_id" to "stored-a", "yolo" to false, "scope_key" to fixture.client.scope.session("stored-a").key))

    // YOLO off right after opening a chat: Hermes saved it, but the check ran before the agent could report it and the change showed as failed.
    @Test fun yoloOffWaitsForTheChatsAgentSoTheChangeCanBeVerified() = runBlocking {
        val fixture = Fixture()
        try {
            val result = yoloOff(fixture)
            assertEquals(listOf("session.resume", "approval.pending", "config.set", "config.get", "session.resume"), fixture.methods.filter { it != "client.capabilities" })
            assertEquals(true, fixture.builtAtWrite)
            assertEquals(false, fixture.yolo)
            assertTrue(result.getBoolean("session_status_known"))
            assertEquals(false, result.getBoolean("session_bypass"))
        } finally { fixture.client.closeNative() }
    }

    @Test fun yoloOffIsNotReportedAsVerifiedWhenTheAgentBuildFailed() = runBlocking {
        val fixture = Fixture(refuseBuild = true)
        try {
            assertNotNull(runCatching { yoloOff(fixture) }.exceptionOrNull())
        } finally { fixture.client.closeNative() }
    }

    // The phone rebuilds its queue of open prompts from what Hermes answers to "what is pending": the first one and the ones behind it. A gateway without session authority
    // answers from its own lists (open approvals, open requests), so the same `waiting` has to come out of them.
    private fun legacyApproval(id: String) = json("request_id" to id, "command" to "Synthetic $id", "choices" to JSONArray().put("once").put("deny"))
    private fun legacyQuestion(id: String) = json("id" to id, "method" to "clarify", "params" to json("questions" to JSONArray().put(json("qid" to "q-$id", "question" to "Synthetic $id"))))

    @Test fun aGatewayWithoutAuthorityListsTheApprovalsBehindTheFirstAsWaiting() = runBlocking {
        val fixture = Fixture().apply { legacyApprovals = JSONArray().put(legacyApproval("req-1")).put(legacyApproval("req-2")).put(legacyApproval("req-3")) }
        try {
            val answer = fixture.client.get("/api/approval/pending", mapOf("session_id" to "stored-a"))
            assertEquals("req-1", answer.getJSONObject("pending").string("approval_id"))
            assertEquals(listOf("req-2", "req-3"), answer.rows("waiting").map { it.string("approval_id") })
        } finally { fixture.client.closeNative() }
    }

    @Test fun aGatewayWithoutAuthorityListsTheQuestionsBehindTheFirstAsWaitingAndLeavesOutOtherRequests() = runBlocking {
        val fixture = Fixture().apply { openRequests = JSONArray().put(legacyQuestion("wire-q1")).put(json("id" to "wire-a", "method" to "approval", "params" to json("request_id" to "req-1"))).put(legacyQuestion("wire-q2")) }
        try {
            val answer = fixture.client.get("/api/clarify/pending", mapOf("session_id" to "stored-a"))
            assertEquals("wire-q1", answer.getJSONObject("pending").string("clarify_id"))
            assertEquals(listOf("wire-q2"), answer.rows("waiting").map { it.string("clarify_id") })
        } finally { fixture.client.closeNative() }
    }

    @Test fun aGatewayWithoutAuthorityWithOneOrNoApprovalListsNothingWaiting() = runBlocking {
        val none = Fixture()
        val one = Fixture().apply { legacyApprovals = JSONArray().put(legacyApproval("req-1")) }
        try {
            val empty = none.client.get("/api/approval/pending", mapOf("session_id" to "stored-a"))
            assertTrue(empty.isNull("pending"))
            assertTrue(empty.rows("waiting").isEmpty())
            val single = one.client.get("/api/approval/pending", mapOf("session_id" to "stored-a"))
            assertEquals("req-1", single.getJSONObject("pending").string("approval_id"))
            assertTrue(single.rows("waiting").isEmpty())
        } finally { none.client.closeNative(); one.client.closeNative() }
    }
}
