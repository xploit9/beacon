package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.voice.finishCallOnce
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A conversation binding names a runtime that lives on the socket that made it; Hermes reaps the runtime once that socket is gone. */
class NativeGatewayBindingTest {
    private class Wire : WebSocket.Factory {
        val methods = mutableListOf<String>()
        val connectionMethods = mutableListOf<MutableList<String>>()
        val connectionFrames = mutableListOf<MutableList<JSONObject>>()
        val responses = mutableListOf<Pair<Int, JSONObject>>()
        var replayResult = json("epoch" to "epoch-a", "events" to JSONArray(), "latest_seq" to 0)
        lateinit var socket: WebSocket
        lateinit var listener: WebSocketListener
        var canonical = false
        var advertiseReady = true
        var strictCanonicalCreate = false
        var canonicalCreateResult: JSONObject? = null
        var canonicalResumeResult: JSONObject? = null
        var dropSteerResponse = false
        var dropAfterSteerRefresh = false
        val acceptedCreates = mutableListOf<JSONObject>()
        var deferActivation = false
        var pendingActivation: Pair<WebSocket, JSONObject>? = null
        var activationResult: JSONObject? = json("session_id" to "runtime-1", "stored_session_id" to "stored-a")
        @Volatile var runtime = "runtime-1"
        /** A runtime Hermes dropped: a call naming it answers 4001, as Hermes does for one that is not in memory. */
        @Volatile var reaped: String? = null
        /** Hermes' text for the 4001: "session not found" when the runtime is gone; other 4001s (a session not owned by this transport) keep the binding. */
        @Volatile var refusal = "session not found"
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(json("ticket" to "fixture-ticket").toString().toResponseBody("application/json".toMediaType())).build() }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val connectionCalls = mutableListOf<String>()
            connectionMethods += connectionCalls
            val connectionIndex = connectionMethods.lastIndex
            val connectionMessages = mutableListOf<JSONObject>()
            connectionFrames += connectionMessages
            this.listener = listener
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    if (!frame.has("method")) { responses += connectionIndex to frame; return true }
                    connectionMessages += frame
                    val method = frame.getString("method")
                    methods += method
                    connectionCalls += method
                    if (method == "session.steer" && dropSteerResponse) {
                        listener.onFailure(this, java.io.IOException("fixture lost response"), null)
                        return true
                    }
                    val gone = method != "session.resume" && frame.getJSONObject("params").optString("session_id") == reaped
                    val reply = json("jsonrpc" to "2.0", "id" to frame.getString("id"))
                    val params = frame.getJSONObject("params")
                    // Match the installed canonical closed create schema and its source Literal.
                    val canonicalCreateFields = setOf("profile", "request_id", "source", "cwd", "row_cwd", "model", "provider", "base_url", "api_key", "toolsets", "reasoning", "max_turns", "ignore_rules", "yolo", "safe_mode", "ignore_user_config", "skills", "checkpoints", "accept_hooks", "pass_session_id", "editor", "title", "hidden", "follow_profile_config", "service_tier", "cwd_explicit", "run_budget", "verbose", "client_env")
                    val invalidCreate = canonical && strictCanonicalCreate && method == "session.create" &&
                        (params.keys().asSequence().any { it !in canonicalCreateFields } ||
                            params.has("source") && params.optString("source") !in setOf("cli", "tui", "gui", "acp", "tool", "oneshot"))
                    if (gone || method == "session.activate" && activationResult == null || invalidCreate) reply.put("error", json("code" to 4001, "message" to if (invalidCreate) "invalid_params" else refusal))
                    else reply.put("result", when (method) {
                        "session.create" -> {
                            acceptedCreates += params.copy()
                            if (canonical && strictCanonicalCreate) canonicalCreateResult?.copy() ?: json("session_id" to "stored-a", "stored_session_id" to "stored-a",
                                "messages" to JSONArray(), "message_count" to 0, "running" to false, "authority_epoch" to 1,
                                "replay_epoch" to "fixture", "last_sequence" to 0, "subscription_id" to "fixture-subscription",
                                "revision" to 1, "execution_generation" to 1, "pending" to JSONArray(), "prompts" to JSONArray(),
                                "info" to json("desktop_protocol" to "hermes-gateway-v1", "profile_name" to "default"))
                            else json("session_id" to runtime, "stored_session_id" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        }
                        "session.resume" -> if (canonical) canonicalResumeResult?.copy() ?: json("session_id" to runtime, "stored_session_id" to "stored-a", "messages" to JSONArray(), "message_count" to 0, "running" to true, "authority_epoch" to 1, "replay_epoch" to "fixture", "last_sequence" to 0, "subscription_id" to "fixture-subscription", "revision" to 1, "execution_generation" to 7, "pending" to JSONArray(), "prompts" to JSONArray(), "info" to json("desktop_protocol" to "hermes-gateway-v1", "profile_name" to "default")) else json("session_id" to runtime, "session_key" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        "prompt.submit" -> if (canonical) json("admission_id" to "admission-1", "ref" to json("profile_id" to "fixture-home", "session_id" to runtime), "sequence" to 1, "status" to "started", "outcome" to JSONObject.NULL, "authority_epoch" to 1, "execution_generation" to 7) else json("status" to "streaming", "user_row_id" to 1)
                        "session.activate" -> activationResult!!
                        "session.events.since" -> replayResult.copy()
                        else -> JSONObject()
                    })
                    if (method == "session.activate" && deferActivation) pendingActivation = this to reply
                    else listener.onMessage(this, reply.toString())
                    if (method == "session.resume" && dropAfterSteerRefresh && connectionCalls.count { it == "session.resume" } > 1)
                        listener.onFailure(this, java.io.IOException("fixture lost connection after refresh"), null)
                    return true
                }
            }
            this.socket = socket
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            if (advertiseReady) listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to if (canonical) json("session_authority" to true) else json())).toString())
            return socket
        }
    }

    // Live 2026-10-27: the socket that created a chat closes once its first prompt is accepted, yet a background read reopened the gateway
    // and the old binding looked valid again, so Beacon kept asking a runtime Hermes had dropped.
    @Test(timeout = 15_000) fun aBindingMadeOnAClosedSocketIsNotRevivedWhenAnotherCallReopensTheGateway() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeCreate(json("source" to "android")).runtimeSessionId)
            wire.client.closeNative()
            wire.runtime = "runtime-2"
            wire.client.nativeRpc("subagent.list")
            assertEquals("A reopened socket holds no runtime of the closed one", "", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals("The stored conversation is resumed instead of the old runtime reused", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(1, wire.methods.count { it == "session.resume" })
            assertEquals("runtime-2", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aBindingStaysWhileItsSocketIsOpen() = runBlocking {
        val wire = Wire()
        try {
            val created = wire.client.nativeCreate(json("source" to "android"))
            assertEquals(created, wire.client.nativeSession("stored-a"))
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals(0, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRuntimeHermesNoLongerHoldsIsForgottenSoNoCallerAddressesItAgain() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"
            val failure = runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("", wire.client.nativeSessionSnapshot().string("session_id"))
            wire.reaped = null
            wire.runtime = "runtime-2"
            assertEquals("The next use resumes the stored conversation", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(2, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aNotOwnedRefusalKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"; wire.refusal = "session not found or not owned by this transport"
            val failure = runCatching { wire.client.nativeRpc("subagent.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("Only Hermes' runtime-gone answer drops the binding", "runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRefusalNamingAnotherRuntimeKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            wire.client.nativeSession("stored-a")
            wire.reaped = "runtime-elsewhere"
            assertTrue(runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-elsewhere")) }.isFailure)
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun reconnectAttachesTheSameRuntimeBeforeReplayWithoutResubmitting() = runBlocking {
        val wire = Wire()
        val first = wire.client.nativeSession("stored-a")
        wire.client.nativeRpc("prompt.submit", json("session_id" to first.runtimeSessionId, "text" to "fixture"))
        val collector = async { runCatching { wire.client.nativeEvents { } } }
        withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val replacement = async { runCatching { wire.client.nativeEvents { } } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.size < 2 || "session.events.since" !in wire.connectionMethods[1]) yield() }
            val calls = wire.connectionMethods[1]
            assertTrue("The replacement must activate before replay", calls.indexOf("session.activate") in 0 until calls.indexOf("session.events.since"))
            assertEquals(first, wire.client.nativeSessionSnapshot().let { nativeSessionBinding(wire.client.scope, it) })
            assertEquals("An accepted prompt is never resubmitted", 1, wire.methods.count { it == "prompt.submit" })
            assertEquals("Recovery must not resume a durable conversation", 1, wire.methods.count { it == "session.resume" })
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun missingOrForeignActivationCannotReplayOrClaimRecovery() = runBlocking {
        for (activation in listOf(null, json("session_id" to "runtime-foreign", "stored_session_id" to "stored-a"),
                json("session_id" to "runtime-1", "stored_session_id" to "foreign"))) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.activationResult = activation
            val replacement = async { runCatching { wire.client.nativeEvents { } } }
            try {
                withTimeout(2_000) { while (wire.connectionMethods.size < 2 || wire.connectionMethods[1].size < 2) yield() }
                assertEquals("Only activation may be attempted on the replacement", listOf("client.capabilities", "session.activate"), wire.connectionMethods[1])
                assertTrue("Unverified runtime cannot be exposed as live", wire.client.nativeSessionSnapshot().length() == 0)
                assertEquals(1, wire.methods.count { it == "session.resume" })
            } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun canonicalReconnectResubscribesWithTheSameDurableIdentity() = runBlocking {
        val wire = Wire().apply { canonical = true; runtime = "stored-a" }
        val binding = wire.client.nativeSession("stored-a")
        val collector = async { runCatching { wire.client.nativeEvents { } } }
        withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val replacement = async { runCatching { wire.client.nativeEvents { } } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.size < 2 || "session.events.since" !in wire.connectionMethods[1]) yield() }
            val methods = wire.connectionMethods[1]
            assertTrue(methods.indexOf("session.resume") in 0 until methods.indexOf("session.events.since"))
            assertFalse("Canonical authority does not support session.activate", "session.activate" in methods)
            assertEquals(binding, nativeSessionBinding(wire.client.scope, wire.client.nativeSessionSnapshot()))
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun cancelledOrClosedRecoveryCannotAdoptALateActivation() = runBlocking {
        for (ending in listOf("cancel", "close", "profile")) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.deferActivation = true
            val replacement = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.pendingActivation == null) yield() }
            when (ending) {
                "cancel" -> replacement.cancelAndJoin()
                "close" -> { wire.client.closeNative(); replacement.await() }
                else -> { wire.client.bindProfile("other"); replacement.await() }
            }
            val (socket, reply) = wire.pendingActivation!!
            wire.listener.onMessage(socket, reply.toString())
            assertTrue("An interrupted recovery must not claim the runtime", wire.client.nativeSessionSnapshot().length() == 0)
            assertFalse("An interrupted recovery must not replay", "session.events.since" in wire.connectionMethods[1])
            wire.client.closeNative()
        }
    }


    @Test(timeout = 15_000) fun prepareReplayAlsoReattachesBeforeReadingTheReplacementStream() = runBlocking {
        val wire = Wire()
        try {
            wire.client.nativeSession("stored-a")
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            wire.client.nativeReplay()
            val calls = wire.connectionMethods[1]
            assertTrue("Replay preparation must activate the original runtime first", calls.indexOf("session.activate") in 0 until calls.indexOf("session.events.since"))
            assertEquals("Replay preparation never resubmits work", 0, wire.methods.count { it == "prompt.submit" })
            assertEquals(1, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun recoveredReplayKeepsSequenceAndInteractiveRequestsOnTheReplacementSocket() = runBlocking {
        val wire = Wire()
        val seq1 = json("session_id" to "runtime-1", "seq" to 1, "type" to "message.delta", "payload" to json())
        val seq2 = json("session_id" to "runtime-1", "seq" to 2, "type" to "message.delta", "payload" to json())
        wire.replayResult = json("epoch" to "epoch-a", "events" to JSONArray().put(seq1), "latest_seq" to 1)
        wire.client.nativeSession("stored-a")
        val seen = mutableListOf<Long>()
        val firstSeen = CompletableDeferred<Unit>()
        val collector = async { runCatching { wire.client.nativeEvents { frame ->
            val seq = frame.optJSONObject("params")?.optLong("seq") ?: 0
            if (seq > 0) { seen += seq; firstSeen.complete(Unit) }
        } } }
        withTimeout(2_000) { firstSeen.await() }; yield()
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val request = json("id" to "rpc-approval", "method" to "approval", "params" to json("session_id" to "runtime-1", "request_id" to "ask-1", "choices" to JSONArray().put("deny")))
        wire.replayResult = json("epoch" to "epoch-a", "events" to JSONArray().put(seq1).put(seq2), "latest_seq" to 2, "open_requests" to JSONArray().put(request))
        val secondSeen = CompletableDeferred<Unit>()
        val askSeen = CompletableDeferred<Unit>()
        val replacement = async { runCatching { wire.client.nativeEvents { frame ->
            if (frame.optString("method") == "approval") askSeen.complete(Unit)
            val seq = frame.optJSONObject("params")?.optLong("seq") ?: 0
            if (seq > 0) { seen += seq; if (seq == 2L) secondSeen.complete(Unit) }
        } } }
        try {
            withTimeout(2_000) { askSeen.await(); secondSeen.await() }
            assertEquals(listOf(1L, 2L), seen)
            val replay = wire.connectionFrames[1].single { it.optString("method") == "session.events.since" }
            assertEquals(1L, replay.getJSONObject("params").getLong("last_seen"))
            assertTrue(wire.client.nativeAnswerServerRequest("approval", "ask-1", "runtime-1", json("choice" to "deny")).optBoolean("ok"))
            assertEquals(1, wire.responses.size)
            assertEquals("Interactive answers belong to the replacement socket", 1, wire.responses.single().first)
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }


    @Test(timeout = 15_000) fun replacementMustAdvertiseTheSameKnownAuthorityBeforeRecovery() = runBlocking {
        for (missingReady in listOf(false, true)) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.canonical = !missingReady
            wire.advertiseReady = !missingReady
            try {
                val recovery = withTimeout(6_000) { runCatching { wire.client.nativeEvents { } } }
                assertTrue("Unknown or changed authority cannot recover", recovery.isFailure)
                assertEquals(listOf("client.capabilities"), wire.connectionMethods[1])
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun canonicalCreateBothEntryPointsUseTheClosedNativeContract() = runBlocking {
        for (direct in listOf(true, false)) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
            try {
                val options = json("title" to "Fixture title", "model" to "fixture-model", "provider" to "fixture-provider", "cwd" to "/fixture")
                val binding = if (direct) wire.client.nativeCreate(options) else wire.client.nativeSession(null)
                assertEquals("stored-a", binding.storedSessionId)
                assertEquals("stored-a", binding.runtimeSessionId)
                val params = wire.acceptedCreates.single()
                assertFalse(params.has("source")); assertFalse(params.has("close_on_disconnect"))
                assertEquals("default", params.getString("profile"))
                if (direct) for (key in listOf("title", "model", "provider", "cwd")) assertEquals(options.get(key), params.get(key))
                val snapshot = wire.client.nativeSessionSnapshot()
                assertEquals("stored-a", snapshot.getString("session_id")); assertEquals("stored-a", snapshot.getString("stored_session_id"))
                assertEquals("fixture-subscription", snapshot.getString("subscription_id"))
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun explicitCanonicalSourceIsPreservedAndThreadsFailBeforeCreate() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
        try {
            val b = wire.client.nativeCreate(json("source" to "tui"))
            assertEquals("stored-a", b.storedSessionId)
            assertEquals("tui", wire.acceptedCreates.single().getString("source"))
            for (thread in listOf("android_thread", "phone")) {
                val before = wire.methods.count { it == "session.create" }
                assertTrue(runCatching { wire.client.nativeCreate(json("source" to thread)) }.isFailure)
                assertEquals("An unsupported thread must never be created as an ordinary chat", before, wire.methods.count { it == "session.create" })
            }
            val before = wire.methods.count { it == "session.create" }
            assertTrue(runCatching { wire.client.nativeRpc("session.create", json("close_on_disconnect" to true)) }.isFailure)
            assertEquals("A meaningful unsupported close policy cannot be silently discarded or sent", before, wire.methods.count { it == "session.create" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun createWaitsForTheIssuingSocketReadyBeforeSending() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; advertiseReady = false }
        val created = async { runCatching { wire.client.nativeCreate(json("title" to "Fixture")) } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.isEmpty()) yield() }
            yield()
            assertFalse("Create cannot be sent before authority readiness", "session.create" in wire.methods)
            wire.listener.onMessage(wire.socket, json("method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
            assertEquals("stored-a", created.await().getOrThrow().storedSessionId)
            assertEquals(1, wire.acceptedCreates.size)
        } finally { created.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 20_000) fun unknownOrCancelledCreateReadinessNeverSendsAMutation() = runBlocking {
        for (ending in listOf("unknown", "cancel", "close", "profile")) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; advertiseReady = false }
            val created = async { runCatching { wire.client.nativeSession(null) } }
            try {
                withTimeout(2_000) { while (wire.connectionMethods.isEmpty()) yield() }
                yield()
                when (ending) {
                    "unknown" -> withTimeout(6_000) { created.await() }
                    "cancel" -> created.cancelAndJoin()
                    "close" -> { wire.client.closeNative(); created.await() }
                    else -> { wire.client.bindProfile("other"); created.await() }
                }
                assertFalse("No create may cross unverified readiness or a changed scope", "session.create" in wire.methods)
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { created.cancelAndJoin(); wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun legacyCreateRetainsItsExistingParameters() = runBlocking {
        for (thread in listOf(false, true)) {
            val wire = Wire()
            try {
                val input = json("title" to "Fixture").apply { if (thread) put("source", "android_thread") }
                val binding = wire.client.nativeCreate(input)
                assertEquals("runtime-1", binding.runtimeSessionId)
                assertEquals("stored-a", binding.storedSessionId)
                val params = wire.acceptedCreates.single()
                assertEquals(if (thread) "android_thread" else "android", params.getString("source"))
                assertFalse(params.getBoolean("close_on_disconnect"))
                assertEquals("default", params.getString("profile")); assertEquals("Fixture", params.getString("title"))
            } finally { wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun malformedCanonicalCreateSnapshotIsUnknownWithoutRetryOrGuessedCleanup() = runBlocking {
        for (result in listOf(json("session_id" to "runtime-foreign", "stored_session_id" to "stored-a"),
                json("session_id" to "stored-a"), json("session_id" to "", "stored_session_id" to "stored-a"),
                json("session_id" to 123, "stored_session_id" to 123),
                json("session_id" to JSONObject.NULL, "stored_session_id" to "stored-a"),
                json("session_id" to "stored-a", "stored_session_id" to JSONObject.NULL),
                json("session_id" to "stored-a", "stored_session_id" to "stored-a", "info" to json("profile_name" to "foreign")),
                json("session_id" to "stored-a", "stored_session_id" to "stored-a", "info" to json("profile_name" to 7)))) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; canonicalCreateResult = result }
            try {
                val error = runCatching { wire.client.nativeCreate(JSONObject()) }.exceptionOrNull()
                assertNotNull("Canonical create must not bind a malformed snapshot", error)
                assertEquals("A sent create with invalid identity has unknown outcome", NATIVE_OUTCOME_UNKNOWN, error!!.message)
                assertFalse(requestNeverSent(error))
                assertEquals("No automatic retry after a create was sent", 1, wire.methods.count { it == "session.create" })
                assertTrue("No guessed cleanup for an untrusted identity", wire.methods.none { it == "session.close" || it == "session.resume" })
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun leasedSteerKeepsTheLiveCollectorAndUsesActualCanonicalGeneration() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val binding = wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            try {
                withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
                val beforeReplay = wire.methods.count { it == "session.events.since" }
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                assertEquals(canonical, lease.authority)
                assertEquals(binding, lease.binding)
                wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture"))
                val calls = wire.connectionFrames.single()
                val steer = calls.single { it.optString("method") == "session.steer" }.getJSONObject("params")
                assertEquals("default", steer.getString("profile"))
                assertEquals(binding.runtimeSessionId, steer.getString("session_id"))
                if (canonical) {
                    assertEquals(7L, steer.getLong("execution_generation"))
                    assertEquals(2, calls.count { it.optString("method") == "session.resume" })
                    assertTrue(calls.indexOfLast { it.optString("method") == "session.resume" } < calls.indexOfLast { it.optString("method") == "session.steer" })
                } else {
                    assertFalse(steer.has("execution_generation"))
                    assertEquals(1, calls.count { it.optString("method") == "session.resume" })
                }
                assertEquals("A steer cannot reset replay", beforeReplay, wire.methods.count { it == "session.events.since" })
                assertTrue("The stream collector remains active", collector.isActive)
                assertEquals(1, wire.methods.count { it == "session.steer" })
            } finally { collector.cancelAndJoin(); wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun canonicalSteerRequiresAValidRunningSnapshotGeneration() = runBlocking {
        val invalid = listOf(
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to false, "execution_generation" to 7),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true, "execution_generation" to 7.5),
            json("session_id" to "stored-a", "stored_session_id" to "foreign", "running" to true, "execution_generation" to 7),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true, "execution_generation" to 7, "info" to json("profile_name" to "foreign"))
        )
        for (result in invalid) {
            val wire = Wire().apply { canonical = true; runtime = "stored-a" }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                wire.canonicalResumeResult = result
                val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
                assertTrue("Invalid or foreign snapshots cannot send a steer", error is NativeTurnNotSent)
                assertEquals(0, wire.methods.count { it == "session.steer" })
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun leasedMutationRejectsForeignOrReplacedConversationBeforeSend() = runBlocking {
        for (changed in listOf("runtime", "other-client", "cursor", "closed", "replaced", "profile")) {
            val wire = Wire()
            try {
                val binding = wire.client.nativeSession("stored-a")
                if (changed == "runtime") {
                    assertTrue(runCatching { wire.client.nativeTurnLease("stored-a", "foreign") }.exceptionOrNull() is NativeTurnNotSent)
                    continue
                }
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                val target = if (changed == "other-client") Wire().client else wire.client
                when (changed) {
                    "cursor" -> wire.client.nativeCreate(JSONObject())
                    "closed" -> wire.client.closeNative()
                    "replaced" -> { wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null); wire.client.nativeRpc("subagent.list") }
                    "profile" -> wire.client.bindProfile("other")
                }
                val error = runCatching { target.nativeTurnRpc(lease, "prompt.submit", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
                assertTrue("A foreign or invalidated lease must fail before mutation: $changed", error is NativeTurnNotSent)
                assertEquals(0, wire.methods.count { it == "prompt.submit" })
                if (target !== wire.client) target.closeNative()
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun lostLeasedMutationResponseRemainsUnknown() = runBlocking {
        val wire = Wire().apply { dropSteerResponse = true }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
            val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
            assertNotNull(error)
            assertFalse("A sent steer with a lost response is not a not-sent error", error is NativeTurnNotSent)
            assertFalse(requestNeverSent(error!!))
            assertEquals(1, wire.methods.count { it == "session.steer" })
        } finally { wire.client.closeNative() }
    }


    @Test(timeout = 15_000) fun leasedPromptSubmitsOnceOnItsBoundConnection() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                val params = json("session_id" to binding.runtimeSessionId, "text" to "fixture").apply {
                    if (canonical) put("submission_id", "fixture-submission")
                }
                val response = wire.client.nativeTurnRpc(lease, "prompt.submit", params)
                if (canonical) {
                    assertEquals("admission-1", response.getString("admission_id"))
                    assertEquals(binding.runtimeSessionId, response.getJSONObject("ref").getString("session_id"))
                } else assertEquals(1, response.getInt("user_row_id"))
                assertEquals(1, wire.connectionFrames.single().count { it.optString("method") == "prompt.submit" })
                assertEquals(1, wire.connectionMethods.size)
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun connectionLostAfterGenerationRefreshDoesNotSendSteer() = runBlocking {
        val wire = Wire().apply { canonical = true; runtime = "stored-a" }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            wire.dropAfterSteerRefresh = true
            val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
            assertTrue("A lost owning connection before steer is not sent", error is NativeTurnNotSent)
            assertEquals(0, wire.methods.count { it == "session.steer" })
            assertEquals("No replacement connection is opened for a mutation", 1, wire.connectionMethods.size)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun leasedRequestCannotNameAnotherRuntime() = runBlocking {
        val wire = Wire()
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            val error = runCatching { wire.client.nativeTurnRpc(lease, "prompt.submit", json("session_id" to "foreign", "text" to "fixture")) }.exceptionOrNull()
            assertTrue(error is NativeTurnNotSent)
            assertEquals(0, wire.methods.count { it == "prompt.submit" })
        } finally { wire.client.closeNative() }
    }


    @Test(timeout = 10000) fun voiceSendFenceOrdersHangupAgainstTheActualSocketSend() = runBlocking {
        val denied = Wire(); val deniedClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val binding = denied.client.nativeSession("stored-a")
            assertTrue(finishCallOnce(deniedClosed) { })
            val response = denied.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                null, true, deniedClosed, { !deniedClosed.get() }, { fail("A closed call cannot claim a send"); false }, { true })
            assertEquals("not_sent", response.getString("status"))
            assertEquals("call_ended", response.getString("reason"))
            assertEquals(0, denied.methods.count { it == "prompt.submit" })
        } finally { denied.client.closeNative() }

        val wire = Wire(); val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>(); val closing = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val binding = wire.client.nativeSession("stored-a")
            val sending = async(Dispatchers.Default) {
                wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    null, true, closed, { !closed.get() }, { entered.complete(Unit); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }, { true })
            }
            withTimeout(2000) { entered.await() }
            val hangup = async(Dispatchers.Default) { closing.complete(Unit); finishCallOnce(closed) { } }
            withTimeout(2000) { closing.await() }; delay(100)
            assertFalse("Hang-up cannot overtake an in-progress guarded socket send", hangup.isCompleted)
            release.countDown()
            assertEquals("streaming", withTimeout(2000) { sending.await() }.getString("status"))
            assertTrue(withTimeout(2000) { hangup.await() })
            assertTrue(closed.get())
            assertEquals("The winning send is not repeated", 1, wire.methods.count { it == "prompt.submit" })
        } finally { release.countDown(); wire.client.closeNative() }
    }


    @Test(timeout = 10000) fun voiceStageFencePreventsUploadWhenCallEndsBeforeStageSend() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val owned = java.util.concurrent.atomic.AtomicBoolean(true)
            try {
                val binding = wire.client.nativeSession("stored-a")
                val result = wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    "fixture-jpeg", true, Any(), { owned.get() }, { fail("A closed call cannot claim prompt submission"); false },
                    { owned.set(false); true })
                assertEquals("not_sent", result.getString("status")); assertEquals("call_ended", result.getString("reason"))
                assertTrue("A closed call cannot stage or submit", wire.methods.none { it in setOf("file.attach", "image.attach_bytes", "prompt.submit") })
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 10000) fun voiceStageFenceDropsRevokedFrameAndStillSubmitsText() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val checks = java.util.concurrent.atomic.AtomicInteger()
            try {
                val binding = wire.client.nativeSession("stored-a")
                val result = wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    "fixture-jpeg", true, Any(), { true }, { true }, { checks.incrementAndGet() == 1 })
                assertEquals(if (canonical) "started" else "streaming", result.getString("status"))
                assertTrue(wire.methods.none { it in setOf("file.attach", "image.attach_bytes") })
                val prompt = wire.connectionFrames.single().single { it.optString("method") == "prompt.submit" }.getJSONObject("params")
                assertTrue(prompt.getString("text").contains("image could not be attached")); assertFalse(prompt.has("attachments"))
            } finally { wire.client.closeNative() }
        }
    }

}
