package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import dev.hermes.android.voice.awaitNativeVoiceAgent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class AuditR8aGatewayTest {
    private class Fixture : WebSocket.Factory {
        val resumes = mutableListOf<JSONObject>()
        val methods = mutableListOf<String>()
        val prompts = mutableListOf<JSONObject>()
        val builderReads = mutableListOf<JSONObject>()
        val buildRequested = CompletableDeferred<Unit>()
        val resumeFrameSizes = mutableListOf<Int>()
        val reads = AtomicInteger()
        val maximumReads = AtomicInteger()
        var agentLazy = false
        var finishBuild = true
        var refuseBuild = false
        var resumeStored = "stored-a"
        var resumeRuntime = "runtime-a"
        var activeStored = "stored-a"
        var activeRuntime = "runtime-a"
        var activeReads = 0
        private var pendingBuild: (() -> Unit)? = null
        fun completeBuild() {
            val complete = checkNotNull(pendingBuild)
            pendingBuild = null
            agentLazy = false
            complete()
        }
        private val legacyPhoto by lazy { "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(ByteArray(7 * 1024 * 1024)) }
        private fun resumeResult(params: JSONObject) = json(
            "session_id" to resumeRuntime, "session_key" to resumeStored, "message_count" to 3,
            "messages_omitted" to params.optBoolean("omit_messages"),
            "messages" to JSONArray().apply {
                if (!params.optBoolean("omit_messages")) {
                    put(json("role" to "user", "text" to if (params.optBoolean("inline_images", true)) legacyPhoto else "[image]", "row_id" to 41))
                    put(json("role" to "user", "text" to "@image /workspace/photo.png", "row_id" to 42))
                    put(json("role" to "assistant", "text" to "Earlier reply", "row_id" to 43))
                }
            }, "info" to json("cwd" to "/workspace", "model" to "fixture-model", "lazy" to agentLazy))
        private fun reply(id: String, result: JSONObject) = json("jsonrpc" to "2.0", "id" to id, "result" to result).toString()
        val legacyResumeFrameSize get() = reply("control", resumeResult(JSONObject())).length
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val body = when {
                    path == "/api/auth/ws-ticket" -> json("ticket" to "fixture-ticket")
                    path == "/api/sessions" -> json("sessions" to JSONArray())
                    path.startsWith("/api/sessions/hidden-") -> {
                        val count = reads.incrementAndGet()
                        maximumReads.updateAndGet { maxOf(it, count) }
                        try {
                            Thread.sleep(20)
                            json("id" to path.substringAfterLast('/'), "profile" to "default", "hidden" to true, "message_count" to 1, "source" to "android")
                        } finally { reads.decrementAndGet() }
                    }
                    else -> error("Unexpected fixture route")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    methods += frame.getString("method")
                    val params = frame.getJSONObject("params")
                    val result = when (frame.getString("method")) {
                        "session.resume" -> {
                            resumes += params.copy()
                            resumeResult(params)
                        }
                        "session.events.since" -> json("epoch" to "fixture-epoch", "latest_seq" to 0, "events" to JSONArray())
                        "session.history" -> json("messages" to resumeResult(JSONObject()).getJSONArray("messages"))
                        "approval.pending" -> {
                            builderReads += params.copy()
                            require(params.getString("session_id") == "runtime-a" && params.getString("profile") == "default")
                            buildRequested.complete(Unit)
                            if (refuseBuild) {
                                listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"),
                                    "error" to json("code" to 5032, "message" to "Fixture builder refused")).toString())
                                return true
                            }
                            val approvals = json("approvals" to JSONArray())
                            if (agentLazy && !finishBuild) {
                                pendingBuild = { listener.onMessage(this, reply(frame.getString("id"), approvals)) }
                                return true
                            }
                            agentLazy = false
                            approvals
                        }
                        "session.active_list" -> {
                            activeReads++
                            json("sessions" to JSONArray().put(json("id" to activeRuntime, "session_key" to activeStored,
                                "status" to if (buildRequested.isCompleted && agentLazy) "starting" else "idle")))
                        }
                        "prompt.submit" -> { prompts += params.copy(); json("status" to "streaming") }
                        "session.list" -> json("sessions" to JSONArray().apply {
                            if (params.optBoolean("include_hidden")) repeat(18) { put(json("id" to "hidden-$it", "message_count" to 1)) }
                        })
                        else -> JSONObject()
                    }
                    val response = reply(frame.getString("id"), result)
                    if (frame.getString("method") == "session.resume") resumeFrameSizes += response.length
                    listener.onMessage(this, response)
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
        }
    }

    // The binding resume must not omit messages: live on 23 Oct 2026, an omit_messages resume left every turn stuck on "Working".
    @Test(timeout = 15_000) fun bindingResumeKeepsHistorySoLiveTurnsReachThePhone() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue("The legacy inline photo must exceed the native 8 MiB frame cap", fixture.legacyResumeFrameSize > 8 * 1024 * 1024)
            repeat(2) { index ->
                val binding = fixture.client.nativeSession("stored-a", lazy = index == 1)
                assertEquals("stored-a", binding.storedSessionId)
                assertEquals(fixture.client.scope, binding.scope)
                assertEquals("runtime-a", binding.runtimeSessionId)
                assertFalse(fixture.resumes.last().getBoolean("omit_messages"))
                assertFalse(fixture.resumes.last().getBoolean("inline_images"))
                assertFalse(fixture.resumes.last().getBoolean("close_on_disconnect"))
                assertEquals("default", fixture.resumes.last().getString("profile"))
                assertEquals("android", fixture.resumes.last().getString("source"))
                assertTrue(fixture.resumeFrameSizes.last() < 8 * 1024 * 1024)
                val snapshot = fixture.client.nativeSessionSnapshot()
                assertEquals(3, snapshot.getInt("message_count"))
                assertFalse(snapshot.getBoolean("messages_omitted"))
                assertEquals("[image]", snapshot.getJSONArray("messages").getJSONObject(0).getString("text"))
                assertEquals("@image /workspace/photo.png", snapshot.getJSONArray("messages").getJSONObject(1).getString("text"))
                assertEquals("Earlier reply", snapshot.getJSONArray("messages").getJSONObject(2).getString("text"))
                assertEquals("/workspace", snapshot.getJSONObject("info").getString("cwd"))
                val accepted = fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Next prompt $index"))
                assertTrue(accepted.getBoolean("accepted"))
                assertEquals("runtime-a", fixture.prompts.last().getString("session_id"))
                assertEquals("Next prompt $index", fixture.prompts.last().getString("text"))
                fixture.client.closeNative()
            }
            assertEquals(2, fixture.resumes.size)
            assertTrue(fixture.resumes.last().getBoolean("lazy"))
        } finally { fixture.client.closeNative() }
    }

    @Test(timeout = 15_000) fun directResumeAlsoKeepsImagesCompactWithoutChangingCallerParameters() = runBlocking {
        val fixture = Fixture()
        val params = json("session_id" to "stored-a", "omit_messages" to false, "close_on_disconnect" to false)
        try {
            val resumed = fixture.client.nativeRpc("session.resume", params)
            assertEquals("[image]", resumed.getJSONArray("messages").getJSONObject(0).getString("text"))
            assertFalse(fixture.resumes.single().getBoolean("inline_images"))
            assertFalse(params.has("inline_images"))
            assertFalse(params.has("profile"))
            assertFalse(params.has("source"))
        } finally { fixture.client.closeNative() }
    }

    @Test(timeout = 10_000) fun eagerResumeAndActiveListLeaveALiveLazyWatchUnbuilt() = runBlocking {
        val fixture = Fixture().apply { agentLazy = true }
        try {
            fixture.client.nativeSession("stored-a", lazy = true)
            repeat(3) {
                val resumed = fixture.client.nativeRpc("session.resume", json("session_id" to "stored-a", "eager_build" to true, "omit_messages" to false))
                assertTrue(resumed.getJSONObject("info").getBoolean("lazy"))
                val active = fixture.client.nativeRpc("session.active_list").getJSONArray("sessions").getJSONObject(0)
                assertEquals("idle", active.getString("status"))
            }
            assertFalse(fixture.buildRequested.isCompleted)
            assertTrue(fixture.prompts.isEmpty())
        } finally { fixture.client.closeNative() }
    }

    @Test(timeout = 10_000) fun voiceAdmissionWaitsForItsAgentWithoutDownloadingLegacyPhotoHistory() = runBlocking {
        val fixture = Fixture().apply { agentLazy = true; finishBuild = false }
        try {
            val binding = fixture.client.nativeSession("stored-a", lazy = true)
            withTimeout(2_000) {
                val admission = async {
                    awaitNativeVoiceAgent(fixture.client, binding)
                    fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Delegated work"))
                }
                fixture.buildRequested.await()
                assertFalse("Admission must wait for the builder", admission.isCompleted)
                assertTrue(fixture.agentLazy)
                assertTrue(fixture.prompts.isEmpty())
                val read = fixture.builderReads.single()
                assertEquals("runtime-a", read.getString("session_id"))
                assertEquals("default", read.getString("profile"))
                assertEquals(setOf("session_id", "profile"), read.keys().asSequence().toSet())
                fixture.completeBuild()
                assertTrue(admission.await().getBoolean("accepted"))
            }
            assertTrue(fixture.activeReads > 0)
            assertEquals(1, fixture.builderReads.size)
            assertTrue(fixture.methods.toString(), fixture.methods.all { it in setOf("client.capabilities", "session.resume", "session.events.since", "session.active_list", "approval.pending", "prompt.submit") })
            assertTrue(fixture.resumes.drop(1).all { it.getBoolean("eager_build") && !it.getBoolean("omit_messages") && !it.getBoolean("inline_images") })
            assertTrue(fixture.resumeFrameSizes.all { it < 8 * 1024 * 1024 })
            assertEquals(1, fixture.prompts.size)
            assertEquals("runtime-a", fixture.prompts.single().getString("session_id"))
            assertEquals("Delegated work", fixture.prompts.single().getString("text"))
        } finally { fixture.client.closeNative() }
    }

    @Test(timeout = 10_000) fun voiceAdmissionRejectsWrongBindingAndBuilderRefusalBeforePrompt() = runBlocking {
        for (failure in listOf("scope", "stored", "runtime", "active-stored", "active-runtime", "refused")) {
            val fixture = Fixture().apply { agentLazy = true }
            try {
                val binding = fixture.client.nativeSession("stored-a", lazy = true)
                when (failure) {
                    "stored" -> fixture.resumeStored = "other"
                    "runtime" -> fixture.resumeRuntime = "other"
                    "active-stored" -> fixture.activeStored = "other"
                    "active-runtime" -> fixture.activeRuntime = "other"
                    "refused" -> fixture.refuseBuild = true
                }
                val target = if (failure == "scope") binding.copy(scope = binding.scope.copy(profile = "foreign")) else binding
                val error = runCatching {
                    awaitNativeVoiceAgent(fixture.client, target)
                    fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Must not send"))
                }.exceptionOrNull()
                assertNotNull(failure, error)
                assertEquals(when (failure) {
                    "scope" -> "Native conversation scope changed"
                    "stored", "runtime" -> "Native conversation identity changed"
                    "active-stored" -> "Native runtime conversation mismatch"
                    "active-runtime" -> "Owned native runtime unavailable"
                    else -> "Fixture builder refused"
                }, error?.message)
                if (failure == "refused") assertEquals(5032, (error as ApiFailure).status)
                assertEquals(if (failure in setOf("scope", "stored", "runtime")) 0 else 1, fixture.builderReads.size)
                assertTrue(fixture.prompts.isEmpty())
                assertTrue(fixture.methods.none { it == "session.history" })
                if (failure == "scope") assertEquals("Reject a foreign binding before another request", 1, fixture.resumes.size)
            } finally { fixture.client.closeNative() }
        }
    }

    @Test(timeout = 10_000) fun voiceAdmissionRechecksBindingAfterTheBuilderCompletes() = runBlocking {
        for (changed in listOf("stored", "runtime")) {
            val fixture = Fixture().apply { agentLazy = true; finishBuild = false }
            try {
                val binding = fixture.client.nativeSession("stored-a", lazy = true)
                withTimeout(2_000) {
                    val admission = async { runCatching {
                        awaitNativeVoiceAgent(fixture.client, binding)
                        fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Must not send"))
                    } }
                    fixture.buildRequested.await()
                    if (changed == "stored") fixture.resumeStored = "other" else fixture.resumeRuntime = "other"
                    fixture.completeBuild()
                    assertEquals("Native conversation identity changed", admission.await().exceptionOrNull()?.message)
                }
                assertEquals(1, fixture.builderReads.size)
                assertTrue(fixture.prompts.isEmpty())
            } finally { fixture.client.closeNative() }
        }
    }

    @Test(timeout = 10_000) fun unfinishedVoiceBuilderHonorsAdmissionDeadlineWithoutPrompt() = runBlocking {
        val fixture = Fixture().apply { agentLazy = true; finishBuild = false }
        try {
            val binding = fixture.client.nativeSession("stored-a", lazy = true)
            assertNull(withTimeoutOrNull(600L) {
                awaitNativeVoiceAgent(fixture.client, binding)
                fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Must not send"))
            })
            assertEquals(1, fixture.builderReads.size)
            fixture.completeBuild()
            assertTrue(fixture.prompts.isEmpty())
        } finally { fixture.client.closeNative() }
    }

    @Test(timeout = 10_000) fun cancelledVoiceBuilderCannotSubmitEvenAfterItFinishes() = runBlocking {
        val fixture = Fixture().apply { agentLazy = true; finishBuild = false }
        try {
            val binding = fixture.client.nativeSession("stored-a", lazy = true)
            val admission = launch {
                awaitNativeVoiceAgent(fixture.client, binding)
                fixture.client.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Must not send"))
            }
            withTimeout(2_000) { fixture.buildRequested.await() }
            admission.cancelAndJoin()
            fixture.completeBuild()
            assertTrue(admission.isCancelled)
            assertEquals(1, fixture.builderReads.size)
            assertTrue(fixture.prompts.isEmpty())
        } finally { fixture.client.closeNative() }
    }

    @Test fun archivedMetadataReadsAreParallelAndBounded() = runBlocking {
        val fixture = Fixture()
        try {
            val archived = fixture.client.get("/api/sessions", mapOf("include_archived" to "1")).getJSONArray("sessions")
            assertEquals(18, archived.length())
            assertTrue("Archived reads should overlap", fixture.maximumReads.get() > 1)
            assertTrue("Archived reads must stay bounded", fixture.maximumReads.get() <= 6)
        } finally { fixture.client.closeNative() }
    }
}
