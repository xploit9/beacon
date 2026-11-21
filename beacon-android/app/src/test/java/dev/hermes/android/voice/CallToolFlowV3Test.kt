package dev.hermes.android.voice

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Calls contract v3 against a fake Hermes serving the contract fixtures (copied from lane A's tests/fixtures/calls_v3): a tool is one
 * request, late results come once over the updates channel, a hand-over is one request, a lost answer is a state and then the result,
 * and a 1.2.0 plugin still gets the 1.2.0 requests.
 */
class CallToolFlowV3Test {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("calls_v3/$name.json")!!.readText())

    private class Hermes(private val parent: CallToolFlowV3Test) : Dispatcher() {
        val requests = CopyOnWriteArrayList<Pair<RecordedRequest, JSONObject>>()
        val pages = LinkedBlockingQueue<JSONObject>()
        @Volatile var invoke: (JSONObject, Int) -> MockResponse = { body, _ -> ok(parent.fixture("invoke_completed").put("request_id", body.getString("request_id"))) }
        @Volatile var decision: (String) -> JSONObject = { JSONObject() }
        @Volatile var status: (String) -> JSONObject = { JSONObject() }
        @Volatile var handover: (JSONObject) -> JSONObject = { body -> parent.fixture("handover_queued").put("request_id", body.getString("request_id"))
            .put("callback_consent", if (body.optBoolean("callback")) "requested" else "unset") }
        /** Hermes is out of reach: every request loses its connection. */
        @Volatile var down = false
        @Volatile var handoverCode = 200
        @Volatile var context: JSONObject? = null
        /** Whether a hand-over request is lost on the way back (its connection drops after the request). */
        @Volatile var handoverLost: () -> Boolean = { false }
        /** How many of the next updates polls lose their connection. */
        @Volatile var updatesLost = 0
        /** While set, Hermes holds every updates poll (its long wait) until it is released. */
        @Volatile var updatesHold: java.util.concurrent.CountDownLatch? = null
        fun ok(value: JSONObject) = MockResponse().setBody(value.toString()).setHeader("Content-Type", "application/json")
        fun paths() = requests.map { it.first.requestUrl!!.encodedPath }
        fun bodies(suffix: String) = requests.filter { it.first.requestUrl!!.encodedPath.endsWith(suffix) }.map { it.second }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            val text = request.body.readUtf8()
            val body = if (text.isBlank()) JSONObject() else JSONObject(text)
            requests += request to body
            if (down) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            return when {
                path.endsWith("/tools/invoke") -> invoke(body, requests.count { it.first.requestUrl!!.encodedPath.endsWith("/tools/invoke") })
                path.endsWith("/updates") && updatesLost > 0 -> { updatesLost--;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                path.endsWith("/updates") && updatesHold != null -> { updatesHold?.await(20, TimeUnit.SECONDS)
                    ok(JSONObject().put("call_id", "call").put("events", JSONArray()).put("next_after", request.requestUrl!!.queryParameter("after")!!.toLong()).put("reset", false).put("closed", false)) }
                path.endsWith("/updates") -> ok(pages.poll(300, TimeUnit.MILLISECONDS)
                    ?: JSONObject().put("call_id", "call").put("events", JSONArray()).put("next_after", request.requestUrl!!.queryParameter("after")!!.toLong()).put("reset", false).put("closed", false))
                path.endsWith("/decision") -> ok(decision(path.split("/").dropLast(1).last()))
                path.contains("/tools/requests/") -> ok(status(path.substringAfterLast("/")))
                path.endsWith("/handover") && handoverLost() -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                path.endsWith("/handover") -> ok(handover(body)).setResponseCode(handoverCode)
                path.endsWith("/context") && context != null -> ok(JSONObject(context.toString()))
                path.endsWith("/calls/call") -> ok(JSONObject().put("jobs", JSONArray()))
                else -> ok(JSONObject())
            }
        }
    }

    private inner class Call(context: JSONObject, clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
        val server = MockWebServer()
        val hermes = Hermes(this@CallToolFlowV3Test)
        val frames = CopyOnWriteArrayList<JSONObject>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val failures = CopyOnWriteArrayList<String>()
        /** The model's audio as the call plays it, in order. */
        val audio = CopyOnWriteArrayList<ByteArray>()
        /** Every provider session the call set up, newest last. */
        val sessions = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        /** How many setups that declare one of the context's own tools the provider refuses with 1007 before setupComplete. */
        @Volatile var refuseDeclaringSetups = 0
        /** How many setups that resume a session the provider refuses (1008, the session is gone) before setupComplete. */
        @Volatile var refuseResumedSetups = 0
        /** The close code of such a refusal (1008: the session is gone; 1011: a transient error). */
        @Volatile var refuseResumedCode = 1008
        /** While set, sending a tool response waits for it (the call's writer stalls, as on a starved phone). */
        @Volatile var holdToolResponses: java.util.concurrent.CountDownLatch? = null
        private val opened = CompletableDeferred<Pair<WebSocket, WebSocketListener>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client: BeaconClient
        val voice: DirectVoice
        init {
            hermes.context = JSONObject(context.toString()).put("profile", "default").put("selected_session_id", "stored")
            server.dispatcher = hermes;server.start()
            client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
                builder.addInterceptor { chain ->
                    val original = chain.request()
                    chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()).build())
                }
            }
            val factory = WebSocket.Factory { request, listener ->
                val socket = object : WebSocket {
                    override fun request() = request
                    override fun queueSize() = 0L
                    override fun send(bytes: ByteString) = false
                    override fun close(code: Int, reason: String?) = true
                    override fun cancel() = Unit
                    override fun send(text: String): Boolean {
                        val frame = JSONObject(text);frames += frame
                        if (frame.has("toolResponse")) holdToolResponses?.await(5, TimeUnit.SECONDS)
                        if (frame.has("setup") && refuseResumedSetups > 0 && frame.getJSONObject("setup").optJSONObject("sessionResumption")?.has("handle") == true) {
                            refuseResumedSetups--;listener.onClosing(this, refuseResumedCode, if (refuseResumedCode == 1008) "BidiGenerateContent session not found or expired" else "Internal error");return true
                        }
                        if (frame.has("setup") && refuseDeclaringSetups > 0 && text.contains("\"notes_search\"")) {
                            refuseDeclaringSetups--;listener.onClosing(this, 1007, "setup.tools[0].function_declarations[12].parameters: synthetic refusal");return true
                        }
                        if (frame.has("setup")) { sessions += this to listener;listener.onMessage(this, JSONObject().put("setupComplete", JSONObject()).toString());opened.complete(this to listener) }
                        return true
                    }
                }
                listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
                socket
            }
            voice = DirectVoice(client, "call", "stored", scope, { audio += it }, { events += it }, { failures += it }, VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced"),
                providerSocketFactory = factory, clock = clock, prefetched = JSONObject(context.toString()).put("profile", "default").put("selected_session_id", "stored"))
        }
        suspend fun start() {
            voice.start()
            withTimeout(3000) { opened.await() }
            withTimeout(3000) { while (events.none { it.optString("state") == "listening" }) delay(5) }
        }
        suspend fun firstSession() = opened.await()
        /** Any server frame on the newest session (a resumption update, a close). */
        fun server(value: JSONObject) { val (socket, listener) = sessions.last();listener.onMessage(socket, value.toString()) }
        /** The live model's server content on the newest session (its turn's end, the owner's transcribed words). */
        fun content(value: JSONObject) { val (socket, listener) = sessions.last();listener.onMessage(socket, json("serverContent" to value).toString()) }
        suspend fun model(id: String, name: String, args: JSONObject) {
            val (socket, listener) = opened.await()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to id, "name" to name, "args" to args)))).toString())
        }
        /** Every function response for [id] as sent, with its scheduling and willContinue. */
        fun responses(id: String) = frames.flatMap { f -> f.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { it.optString("id") == id }
        /** What the live model was answered for function call [id], in order. */
        fun told(id: String) = frames.flatMap { f -> f.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { it.optString("id") == id }.map { it.getJSONObject("response") }
        suspend fun until(timeoutMs: Long = 4000, predicate: () -> Boolean) = assertTrue(withTimeoutOrNull(timeoutMs) { while (!predicate()) delay(10);true } == true)
        fun requestId(id: String) = java.util.UUID.nameUUIDFromBytes("call:$id".toByteArray(Charsets.UTF_8)).toString()
        /** The tool responses sent, each as its list of function responses. */
        fun toolResponses() = frames.mapNotNull { it.optJSONObject("toolResponse")?.optJSONArray("functionResponses") }
            .map { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        /** The function responses that asked the model to answer (WHEN_IDLE), in order. */
        fun prompts() = toolResponses().flatten().filter { it.optString("scheduling") == "WHEN_IDLE" }
        fun close() { voice.close();scope.cancel();client.closeNative();server.shutdown() }
    }

    private fun v3() = fixture("context_v3")
    private fun v2() = VersionSkew120PluginTest.v120Context()

    @Test(timeout = 15000) fun aDeclaredToolIsOneRequestAndItsResultTheFunctionsOnlyAnswer() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            delay(300)
            val sent = call.hermes.bodies("/tools/invoke").single()
            assertEquals("notes_search", sent.getString("tool"));assertEquals(8000, sent.getInt("wait_ms"))
            assertEquals(call.requestId("f-1"), sent.getString("request_id"));assertEquals("groceries", sent.getJSONObject("arguments").getString("query"))
            assertFalse("A v3 invoke never carries a v2 tool_id", sent.has("tool_id"))
            assertTrue("No tool search first", call.hermes.paths().none { it.endsWith("/tools") })
            val invoke = call.hermes.requests.first { it.first.requestUrl!!.encodedPath.endsWith("/tools/invoke") }.first
            assertEquals("3", invoke.getHeader("X-Beacon-Call-Contract"))
            val told = call.told("f-1").single()
            assertEquals("completed", told.getString("status"));assertFalse(told.getBoolean("will_update"))
            assertEquals("Synthetic groceries", told.getJSONObject("result").getJSONArray("notes").getJSONObject(0).getString("title"))
            assertTrue("The tool window shows the result", call.events.any { it.optString("type") == "tool_receipt" && it.optString("status") == "completed" })
        } finally { call.close() }
    }

    @Test(timeout = 14000) fun aFinalNativeUncertaintySettlesTheModelOnce() = runBlocking {
        val call = Call(v3())
        val reads = java.util.concurrent.atomic.AtomicInteger()
        try {
            // Matches gateway_tools.receipt_v3 for a dispatched mutation with an unknown outcome.
            fun terminal(id: String) = json("request_id" to id, "tool_id" to fixture("invoke_running").getString("tool_id"),
                "tool_name" to "notes_search", "tool" to "notes_search", "read_only" to false,
                "status" to "uncertain", "ran_on_hermes" to true, "error_code" to "outcome_unknown",
                "reason" to "Tool outcome unavailable", "server_ms" to JSONObject())
            call.hermes.invoke = { body, _ -> call.hermes.ok(terminal(body.getString("request_id"))) }
            call.hermes.status = { id -> reads.incrementAndGet(); terminal(id) }
            call.start()
            call.model("server-unknown", "notes_search", json("query" to "fixture"))
            call.until { call.told("server-unknown").any { it.optString("status") == "uncertain" } }
            delay(2200)
            val requestId = call.requestId("server-unknown")
            val duplicate = terminal(requestId).put("type", "receipt").put("seq", 1)
            call.hermes.pages.put(json("call_id" to "call", "events" to JSONArray().put(duplicate), "next_after" to 1, "closed" to false))
            delay(400)
            val final = call.told("server-unknown").count { !it.getBoolean("will_update") }
            assertEquals(0, reads.get())
            assertEquals(1, call.hermes.bodies("/tools/invoke").size)
            assertEquals("An authoritative native terminal outcome must settle the function", 1, final)
        } finally { call.close() }
    }

    @Test(timeout = 24000) fun uncertainGatewayReceiptIsReadUntilHermesSettlesTheSameRequestOnce() = runBlocking {
        for (terminal in listOf("completed", "failed")) {
            val call = Call(v3())
            val release = java.util.concurrent.atomic.AtomicBoolean()
            try {
                fun receipt(id: String, status: String) = fixture(if (status == "completed") "invoke_completed" else "invoke_running")
                    .put("request_id", id).put("status", status).apply {
                        if (status == "failed") put("result", json("error" to "Synthetic native refusal"))
                    }
                call.hermes.invoke = { body, _ -> call.hermes.ok(receipt(body.getString("request_id"), "running")).setHeadersDelay(250, TimeUnit.MILLISECONDS) }
                call.hermes.status = { id -> receipt(id, if (release.get()) terminal else "running") }
                call.start()
                call.model("late-read", "notes_search", json("query" to "fixture"))
                call.until { call.told("late-read").any { it.optString("status") == "running" } }
                call.hermes.down = true
                call.until(7000) { call.told("late-read").any { it.optString("status") == "lost_result" } }
                release.set(true)
                call.hermes.down = false
                call.until(6000) { call.told("late-read").any { it.optString("status") == terminal } }
                delay(250)
                val replies = call.told("late-read")
                assertTrue(replies.first().getBoolean("will_update"))
                assertEquals(listOf(terminal), replies.filter { !it.getBoolean("will_update") }.map { it.getString("status") })
                val answer = replies.single { !it.getBoolean("will_update") }
                assertEquals(call.requestId("late-read"), answer.getString("request_id"))
                assertEquals(receipt(call.requestId("late-read"), terminal).getJSONObject("result").toString(), answer.getJSONObject("result").toString())
                assertEquals(1, call.hermes.bodies("/tools/invoke").size)
            } catch (failure: AssertionError) {
                throw AssertionError("terminal=$terminal, replies=${call.told("late-read")}, requests=${call.hermes.paths().distinct()}", failure)
            } finally { call.close() }
        }
    }

    @Test(timeout = 22000) fun aLostResultIsReadAgainIfTheFirstRecoveryReadIsStillRunning() = runBlocking {
        val call = Call(v3())
        val ready = java.util.concurrent.atomic.AtomicBoolean()
        val statusReads = java.util.concurrent.atomic.AtomicInteger()
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.hermes.status = { id ->
                statusReads.incrementAndGet()
                fixture(if (ready.get()) "invoke_completed" else "invoke_running").put("request_id", id)
            }
            call.start()
            call.model("lost-read", "notes_search", json("query" to "fixture"))
            call.until { call.told("lost-read").any { it.optString("status") == "running" } }
            call.hermes.down = true
            call.until(7000) { call.told("lost-read").any { it.optString("status") == "lost_result" } }
            call.hermes.down = false
            call.until(6000) { statusReads.get() >= 1 }
            ready.set(true)
            call.until(6000) { call.told("lost-read").any { it.optString("status") == "completed" } }
            assertEquals(1, call.hermes.bodies("/tools/invoke").size)
            assertEquals(1, call.told("lost-read").count { !it.getBoolean("will_update") })
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aToolStillRunningIsAStateAndItsLateResultComesOnceOverTheUpdates() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            val running = call.told("f-1").single()
            // A running answer within 8 s of the call is quiet progress (on a real v3 plugin it comes only after its 8 s wait).
            assertEquals("running", running.getString("status"));assertTrue(running.getBoolean("will_update"));assertEquals(true, running.get("ran_on_hermes"))
            assertEquals("SILENT", call.responses("f-1").single().getString("scheduling"))
            val page = fixture("updates_page")
            val completed = page.getJSONArray("events").getJSONObject(2).put("request_id", call.requestId("f-1"))
            val delivery = JSONObject(page.toString()).put("events", JSONArray().put(completed)).put("closed", false)
            // At least once: the same page arrives twice, and the reset snapshot carries it a third time.
            call.hermes.pages += delivery;call.hermes.pages += delivery
            call.hermes.pages += JSONObject(fixture("updates_reset").toString()).put("events", JSONArray().put(JSONObject(completed.toString()))).put("closed", false)
            call.until { call.told("f-1").size >= 2 }
            call.until { call.hermes.pages.isEmpty() }
            delay(500)
            assertEquals(listOf("running", "completed"), call.told("f-1").map { it.getString("status") })
            assertFalse(call.told("f-1").last().getBoolean("will_update"))
            assertEquals("Still one invoke: the result came by itself", 1, call.hermes.bodies("/tools/invoke").size)
            assertTrue(call.hermes.paths().any { it.endsWith("/calls/call/updates") })
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun anApprovalIsAStateUntilTheOwnersDecisionBringsTheResult() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.hermes.decision = { id -> fixture("invoke_completed").put("request_id", id).put("tool_id", "c3".repeat(32)).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create").put("read_only", false) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until(7000) { call.told("f-2").isNotEmpty() && call.events.any { it.optString("type") == "tool_approval" } }
            val waiting = call.told("f-2").single()
            assertEquals("needs_approval", waiting.getString("status"));assertTrue(waiting.getBoolean("will_update"))
            assertEquals("proposal-synthetic-0000000000000000000000000000", waiting.getJSONObject("proposal").getString("id"))
            call.voice.decideTool(call.requestId("f-2"), "proposal-synthetic-0000000000000000000000000000", "approve")
            call.until { call.told("f-2").size >= 2 }
            assertEquals(listOf("needs_approval", "completed"), call.told("f-2").map { it.getString("status") })
            assertEquals("The decision waits on Hermes for the result", 8000, call.hermes.bodies("/decision").single().getInt("wait_ms"))
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aHandOverIsOneRequestAndTheJobsResultIsTheFinalAnswer() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("w-1", "hermes_work", json("text" to "Find three synthetic flights and put them in the chat.", GeminiWire.OWNER_ASKED_FOR_CALL_BACK to true))
            call.until { call.told("w-1").isNotEmpty() }
            val admitted = call.told("w-1").single()
            // Device round 1, item 1: Gemini Live says nothing until the function answers, so the admission is said at once.
            assertEquals("handed_over", admitted.getString("status"));assertEquals("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4", admitted.getString("job_id"))
            assertEquals("WHEN_IDLE", call.responses("w-1").single().getString("scheduling"));assertTrue(call.responses("w-1").single().getBoolean("willContinue"))
            assertEquals("requested", admitted.getString("callback_consent"));assertTrue(admitted.getBoolean("will_update"))
            // Item 2: the owner asked in the request itself, so it is on in one go and there is nothing to ask.
            assertEquals("on", admitted.getString("call_back"));assertFalse(admitted.has("call_back_question"))
            // Device round 2, item 4: the model still asked; the answer now says plainly that the owner asked and it is on.
            assertTrue(admitted.getString("call_back_note").contains("already on"))
            // Lane D's timeline and fault suite see the hand-over as a hermes_work receipt the timeline maps to a 1.2.0 value.
            val receipt = call.events.single { it.optString("type") == "tool_receipt" && it.optString("tool_name") == "hermes_work" }
            assertEquals("running", timelineToolReceipt(receipt)!!.getString("status"))
            assertNull("A hand-over never takes the call screen's tool chip, as in 1.2.0 (REVIEW-INT-121-2 finding 1)", CallToolReceipt.from(receipt))
            val sent = call.hermes.bodies("/handover").single()
            assertEquals(call.requestId("w-1"), sent.getString("request_id"));assertEquals("background", sent.getString("mode"));assertTrue(sent.getBoolean("callback"))
            assertTrue("No 1.2.0 hand-over steps", call.hermes.paths().none { it.endsWith("/work") || it.endsWith("/jobs") || it.endsWith("/ws-ticket") })
            val job = fixture("updates_page").getJSONArray("events").getJSONObject(3)
            call.hermes.pages += JSONObject().put("call_id", "call").put("events", JSONArray().put(job)).put("next_after", 4).put("reset", false).put("closed", false)
            call.until { call.told("w-1").size >= 2 }
            val done = call.told("w-1").last()
            assertEquals("completed", done.getString("status"));assertFalse(done.getBoolean("will_update"))
            assertTrue(done.getString("result").startsWith("Synthetic summary"))
        } finally { call.close() }
    }

    // REVIEW-INT-121 finding 2: after a lost answer the resend and the waiting updates poll race; the job's result may come first.
    @Test(timeout = 20000) fun aJobResultThatArrivesBeforeTheHandOverAnswerIsStillTheFinalAnswer() = runBlocking {
        val call = Call(v3())
        try {
            val job = fixture("updates_page").getJSONArray("events").getJSONObject(3)
            call.hermes.handover = { body ->
                call.hermes.pages += JSONObject().put("call_id", "call").put("events", JSONArray().put(JSONObject(job.toString()).put("request_id", body.getString("request_id"))))
                    .put("next_after", 4).put("reset", false).put("closed", false)
                val deadline = System.currentTimeMillis() + 3000
                while (call.hermes.pages.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                Thread.sleep(500)
                fixture("handover_queued").put("request_id", body.getString("request_id"))
            }
            call.start()
            call.model("w-1", "hermes_work", json("text" to "Find three synthetic flights and put them in the chat."))
            call.until(6000) { call.told("w-1").isNotEmpty() }
            delay(1500)
            assertEquals("the job page was taken", 0, call.hermes.pages.size)
            assertEquals("The job's result is the answer; the late admission changes nothing", listOf("completed"), call.told("w-1").map { it.getString("status") })
            assertTrue(call.told("w-1").single().getString("result").startsWith("Synthetic summary"))
        } finally { call.close() }
    }

    // REVIEW-INT-121 finding 3, 1.2.0 plugin: a tool is quiet while it has just started, still_running once after 8 s, then its result.
    @Test(timeout = 25000) fun onA120PluginAToolIsQuietUntilTheNoticeThenStillRunningOnce() = runBlocking {
        val call = Call(v2())
        try {
            val started = System.currentTimeMillis()
            call.hermes.invoke = { body, _ -> call.hermes.ok(JSONObject().put("request_id", body.getString("request_id")).put("tool_id", "b".repeat(64)).put("tool_name", "notes_search").put("status", "running")) }
            call.hermes.status = { id -> JSONObject().put("request_id", id).put("tool_id", "b".repeat(64)).put("tool_name", "notes_search")
                .apply { if (System.currentTimeMillis() - started < 10_000) put("status", "running") else put("status", "completed").put("result", JSONObject().put("ok", true)) } }
            call.start()
            call.model("v-2", "invoke_tool", json("tool_id" to "b".repeat(64), "arguments" to "{\"query\":\"groceries\"}"))
            call.until { call.responses("v-2").isNotEmpty() }
            assertEquals("running", call.told("v-2").first().getString("status"));assertEquals("SILENT", call.responses("v-2").first().getString("scheduling"))
            call.until(15_000) { call.told("v-2").size >= 3 }
            assertEquals(listOf("running", "still_running", "completed"), call.told("v-2").map { it.getString("status") })
            assertEquals("WHEN_IDLE", call.responses("v-2")[1].getString("scheduling"))
            // Device round 3: the owner hears it is going on at the notice, once (a later "running" from Hermes is the same state).
            assertTrue("still_running at the notice (CALL_NOTICE_MS, 3 s)", call.told("v-2")[1].getLong("elapsed_ms") in 3_000L until CALL_INVOKE_WAIT_MS)
        } finally { call.close() }
    }

    // REVIEW-INT-121 finding 5: a setup the provider refuses because of the profile's own tools is tried once more without them.
    @Test(timeout = 15000) fun aSetupRefusedWithTheProfilesToolsIsTriedOnceWithoutThem() = runBlocking {
        val call = Call(v3())
        try {
            call.refuseDeclaringSetups = 1
            call.start()
            val setups = call.frames.filter { it.has("setup") }
            assertEquals(2, setups.size)
            assertTrue(setups[0].toString().contains("\"notes_search\""))
            val second = setups[1].toString()
            assertFalse("the second setup declares none of the profile's tools", second.contains("\"notes_search\""))
            assertTrue("they stay reachable", second.contains("\"discover_tools\"") && second.contains(CallInstruction.DISCOVER.trim()))
            assertTrue("the call goes on", call.events.any { it.optString("state") == "listening" })
            assertTrue(call.events.none { it.optString("type") == "error" })
            // REVIEW-INT-121-2 finding 3: the stage log of the retry says how many of the profile's tools it left out.
            val stages = call.events.filter { it.optString("type") == "media_diagnostic" && it.optString("stage") == "provider_setup_sent" }
            assertEquals(v3().getJSONArray("tool_declarations").length(), stages.last().optInt("declarations_dropped"))
        } finally { call.close() }
    }

    // REVIEW-INT-121 finding 13: tools the setup leaves out are counted in the phone's stage log (the fault suite reads it).
    @Test(timeout = 15000) fun theDeclarationsASetupLeavesOutAreCountedInTheStageLog() = runBlocking {
        val context = v3().apply { getJSONArray("tool_declarations").put(JSONObject().put("name", "bad.name").put("description", "Synthetic.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())).put("read_only", true).put("tool_id", "e".repeat(64))) }
        val call = Call(context)
        try {
            call.start()
            val sent = call.events.first { it.optString("type") == "media_diagnostic" && it.optString("stage") == "provider_setup_sent" }
            assertEquals(1, sent.getInt("declarations_dropped"))
        } finally { call.close() }
    }

    // REVIEW-INT-121-2 finding 2: after an outage, a hand-over that gets in 8 s after the call is spoken at once, not 8 s later again.
    @Test(timeout = 25000) fun aHandOverAdmittedAfterAnOutageIsSpokenAtOnce() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            // Hermes is out of reach for 8.5 s: the hand-over and the updates poll lose their connections.
            call.hermes.down = true
            call.model("w-1", "hermes_work", json("text" to "Find three synthetic flights and put them in the chat."))
            call.scope.launch { delay(8_500);call.hermes.down = false }
            call.until(18_000) { call.told("w-1").size >= 2 }
            assertEquals(listOf("hermes_unreachable", "handed_over"), call.told("w-1").map { it.getString("status") })
            assertEquals("WHEN_IDLE", call.responses("w-1")[1].getString("scheduling"))
            assertTrue("one hand-over, resent with the same request id", call.hermes.bodies("/handover").map { it.getString("request_id") }.toSet().size == 1)
        } finally { call.close() }
    }

    // REVIEW-INT-121-2 finding 4: the call says whether Hermes can be reached now, not what a state that is already out of date said.
    @Test(timeout = 15000) fun theLinkLineFollowsTheLinkNotALateState() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            // The race: a function's hermes_unreachable reaches the tool window after another answer brought the link back.
            val window = DirectVoice::class.java.getDeclaredMethod("toolWindow", String::class.java, String::class.java, JSONObject::class.java).apply { isAccessible = true }
            window.invoke(call.voice, "late-1", "notes_search", fixture("link_state_hermes_unreachable"))
            assertEquals(listOf(true), call.events.filter { it.optString("type") == "hermes_link" }.map { it.getBoolean("reachable") })
        } finally { call.close() }
    }

    // REVIEW-INT-121-2 finding 5: a function of a replaced provider session is told only its final result, as data; a state is not
    // said there as an "outcome".
    @Test(timeout = 15000) fun aStateForAFunctionOfAReplacedSessionIsNotSaidAsAnOutcome() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            // The provider session is replaced (a Gemini session without a resumption handle starts afresh).
            val (first, listener) = call.firstSession()
            listener.onClosing(first, 1000, "Synthetic reconnect")
            call.until(6000) { call.frames.count { it.has("setup") } >= 2 }
            val replaced = call.frames.size
            // The link is lost: the waiting function's lost_result now belongs to a session that never saw it. Hermes is out of reach until
            // the loss is told (past LINK_STATE_SETTLE_MS). Follow-up 16: losing only the updates polls no longer loses the link for long, as
            // the lost link's probe finds Hermes answering other requests (device round 4, F1).
            call.hermes.down = true
            call.until(10_000) { call.events.any { it.optString("type") == "hermes_link" && !it.getBoolean("reachable") } }
            call.hermes.down = false
            // Hermes answers again and the result comes; that final result is said to the new session as data.
            val completed = fixture("updates_page").getJSONArray("events").getJSONObject(2).put("request_id", call.requestId("f-1"))
            call.hermes.pages += JSONObject(fixture("updates_page").toString()).put("events", JSONArray().put(completed)).put("closed", false)
            call.until(6000) { call.frames.drop(replaced).any { it.toString().contains("Verified earlier notes_search tool outcome") } }
            delay(300)
            val outcomes = call.frames.drop(replaced).map { it.toString() }.filter { it.contains("Verified earlier notes_search tool outcome") }
            assertTrue("a state went to the new session as an outcome: $outcomes", outcomes.none { it.contains("lost_result") })
            assertEquals("only the final result", 1, outcomes.size);assertTrue(outcomes.single().contains("completed"))
        } finally { call.close() }
    }

    // Device round 1, item 1: the owner hears of the hand-over at once (WHEN_IDLE), with the call back off and the one question to ask;
    // no later state of it is said (before: a quiet admission, then still_running 8 s later); the job's result is the final answer.
    @Test(timeout = 25000) fun aHandOverIsSaidAtOnceAndOnlyItsResultFollows() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            val asked = System.currentTimeMillis()
            call.model("w-1", "hermes_work", json("text" to "Find three synthetic flights and put them in the chat."))
            call.until { call.told("w-1").isNotEmpty() }
            assertTrue("said within 2 s", System.currentTimeMillis() - asked < 2000)
            val admitted = call.told("w-1").single()
            assertEquals("handed_over", admitted.getString("status"));assertEquals("WHEN_IDLE", call.responses("w-1").single().getString("scheduling"))
            assertTrue(call.responses("w-1").single().getBoolean("willContinue"))
            assertEquals("off", admitted.getString("call_back"));assertTrue(admitted.has("call_back_question"));assertFalse(admitted.has("call_back_note"))
            assertFalse("no call back was asked of Hermes", call.hermes.bodies("/handover").single().has("callback"))
            delay(8_700)
            assertEquals("no later state is said", 1, call.told("w-1").size)
            val job = fixture("updates_page").getJSONArray("events").getJSONObject(3)
            call.hermes.pages += JSONObject().put("call_id", "call").put("events", JSONArray().put(job)).put("next_after", 4).put("reset", false).put("closed", false)
            call.until { call.told("w-1").size >= 2 }
            assertEquals(listOf("handed_over", "completed"), call.told("w-1").map { it.getString("status") })
        } finally { call.close() }
    }

    // Device round 1, item 2: the model turned the call back on in the turn where it asked. A call back goes on only with the owner's
    // words after that turn; this is structural (turn ends and owner speech), not a reading of the words.
    @Test(timeout = 15000) fun aCallBackTurnedOnInTheTurnThatAsksIsRefused() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            call.model("cb-1", "hermes_callback", json("enabled" to true))
            call.until { call.told("cb-1").isNotEmpty() }
            val told = call.told("cb-1").single()
            assertEquals("failed", told.getString("status"));assertEquals("owner_has_not_answered", told.getString("detail"));assertEquals("off", told.getString("call_back"))
            assertTrue("nothing reached Hermes", call.hermes.bodies("/callback").isEmpty())
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aYesAfterTheQuestionTurnsTheCallBackOnAndSilenceDoesNot() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            // The model's turn (its question) ends and the owner says nothing: a call back now is refused.
            call.content(json("turnComplete" to true))
            call.model("cb-1", "hermes_callback", json("enabled" to true))
            call.until { call.told("cb-1").isNotEmpty() }
            assertEquals("owner_has_not_answered", call.told("cb-1").single().getString("detail"))
            // The owner answers; then it goes on.
            call.content(json("inputTranscription" to json("text" to "Yes, call me back.")))
            call.model("cb-2", "hermes_callback", json("enabled" to true))
            call.until { call.hermes.bodies("/callback").isNotEmpty() }
            assertTrue(call.hermes.bodies("/callback").single().getBoolean("enabled"))
            // REVIEW-E3 finding 1: one answer turns it on once; another turn-on needs another question and answer.
            call.model("cb-3", "hermes_callback", json("enabled" to true))
            call.until { call.told("cb-3").isNotEmpty() }
            assertEquals("owner_has_not_answered", call.told("cb-3").single().getString("detail"))
            assertEquals(1, call.hermes.bodies("/callback").size)
        } finally { call.close() }
    }

    // REVIEW-E3 finding 1, order A (the reviewer's proof A, inverted): the model asks and turns the call back on in the same turn; the turn
    // ends after its call and the owner says no within the grace. The question's turn had not ended when the call came: refused.
    @Test(timeout = 15000) fun aCallBackTurnedOnBeforeTheAskingTurnEndsIsRefusedEvenIfTheOwnerSpeaksAfter() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            val owner = launch { delay(300);call.content(json("turnComplete" to true));delay(300);call.content(json("inputTranscription" to json("text" to "No, don't call me."))) }
            call.model("cb-1", "hermes_callback", json("enabled" to true))
            owner.join()
            call.until { call.told("cb-1").isNotEmpty() }
            delay(1_000)
            assertEquals("owner_has_not_answered", call.told("cb-1").single().getString("detail"))
            assertTrue("the owner's no turned nothing on", call.hermes.bodies("/callback").none { it.optBoolean("enabled", true) })
        } finally { call.close() }
    }

    // REVIEW-E3 finding 1, order B (the reviewer's proof B, inverted): the first hand-over's question was answered; a second hand-over and a
    // call back in one turn, with no question for it. Each hand-over needs its own question: refused.
    @Test(timeout = 15000) fun aSecondHandOverNeedsItsOwnQuestionBeforeItsCallBack() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            call.content(json("turnComplete" to true))
            call.content(json("inputTranscription" to json("text" to "No thanks.")))
            call.hermes.handover = { body -> Thread.sleep(800);fixture("handover_queued").put("request_id", body.getString("request_id")).put("callback_consent", "unset") }
            call.model("w-2", "hermes_work", json("text" to "Book a synthetic table."))
            call.model("cb-2", "hermes_callback", json("enabled" to true))
            call.until { call.told("cb-2").isNotEmpty() }
            delay(300)
            assertEquals("owner_has_not_answered", call.told("cb-2").single().getString("detail"))
            assertTrue(call.hermes.bodies("/callback").none { it.optBoolean("enabled", true) })
        } finally { call.close() }
    }

    // Device round 3 (F3): a ready result waits for a slow call the model is also waiting on, but only until the notice (CALL_NOTICE_MS):
    // then it goes in the same tool response as the slow call's "still running", one answer from the model, and the slow result later.
    // (Replaces REVIEW-E4 P2-1's 1 s settle, which split a frame of Hermes reads into a turn per result.)
    @Test(timeout = 20000) fun aReadyResultWaitsForASlowCallOnlyUntilTheNoticeAndGoesWithIt() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "session_search") Thread.sleep(5_000)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            val asked = System.currentTimeMillis()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "fast", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "slow", "name" to "session_search", "args" to json("query" to "invoices"))))).toString())
            call.until(5000) { call.responses("fast").isNotEmpty() }
            val at = System.currentTimeMillis() - asked
            assertTrue("held until the notice: $at ms", at in 2_800..4_500)
            val release = call.toolResponses().single { rows -> rows.any { it.getString("id") == "fast" } }
            assertEquals(setOf("fast", "slow"), release.map { it.getString("id") }.toSet())
            assertEquals("one answer for both", listOf("SILENT", "WHEN_IDLE"), release.map { it.getString("scheduling") })
            assertEquals("still_running", release.single { it.getString("id") == "slow" }.getJSONObject("response").getString("status"))
            call.until(10_000) { call.told("slow").size >= 2 }
            assertEquals("completed", call.told("slow").last().getString("status"))
            assertEquals("one answer per release", 2, call.prompts().size)
        } finally { call.close() }
    }

    // REVIEW-E4 P2-1: three fast reads in one frame still make one spoken turn: two quietly, then the last spoken.
    @Test(timeout = 15000) fun threeFastReadsInOneFrameMakeOneTurn() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "session_search") Thread.sleep(300)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "r-a", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "r-b", "name" to "session_search", "args" to json("query" to "invoices")))
                .put(json("id" to "r-c", "name" to "notes_search", "args" to json("query" to "receipts"))))).toString())
            call.until(4000) { listOf("r-a", "r-b", "r-c").all { call.responses(it).isNotEmpty() } }
            val finals = listOf("r-a", "r-b", "r-c").map { call.responses(it).single() }
            assertEquals(1, finals.count { it.getString("scheduling") == "WHEN_IDLE" })
            assertEquals(2, finals.count { it.getString("scheduling") == "SILENT" })
        } finally { call.close() }
    }

    // Asked again while the first waits for the owner's approval: the approval is said once, by the first request's own state; the answer to
    // the repeated ask joins the context quietly (device round 3, F2: one spoken approval per approval; it replaces REVIEW-E4 P2-1's
    // spoken repeat, which said the approval a second time).
    @Test(timeout = 15000) fun aRequestAskedAgainWhileItWaitsForApprovalIsAnsweredQuietly() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("w-1", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until { call.events.any { it.optString("type") == "tool_approval" } }
            call.model("w-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            // The answer goes with the approval's own state, after its settle (APPROVAL_STATE_SETTLE_MS).
            call.until(8000) { call.responses("w-2").isNotEmpty() }
            val again = call.responses("w-2").single()
            assertEquals("needs_approval", again.getJSONObject("response").getString("current_status"))
            assertEquals("SILENT", again.getString("scheduling"))
            call.until(6000) { call.responses("w-1").isNotEmpty() }
            assertEquals("the approval is said once, by the first request", listOf("w-1"), call.prompts().map { it.getString("id") })
        } finally { call.close() }
    }

    // REVIEW-E4 P3-1 (proofG, inverted): a frame carrying the reply's call back and the turn's end (Grok's response.done) keeps the yes.
    @Test(timeout = 15000) fun aYesWhoseCallComesWithTheTurnEndIsAccepted() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            call.content(json("turnComplete" to true))
            call.content(json("inputTranscription" to json("text" to "Yes, call me back.")))
            call.server(json("serverContent" to json("turnComplete" to true), "toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "cb-1", "name" to "hermes_callback", "args" to json("enabled" to true))))))
            call.until { call.hermes.bodies("/callback").isNotEmpty() }
            assertTrue(call.hermes.bodies("/callback").single().getBoolean("enabled"))
        } finally { call.close() }
    }

    // REVIEW-E4 P3-2: a transient close of a resumed setup tries the handle once more (the model keeps its context); refused again, a new
    // session starts.
    @Test(timeout = 25000) fun aTransientCloseOfAResumedSessionTriesTheHandleOnceMore() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.server(json("sessionResumptionUpdate" to json("newHandle" to "synthetic-handle-1", "resumable" to true)))
            call.refuseResumedCode = 1011
            call.refuseResumedSetups = 2
            val (socket, listener) = call.sessions.last()
            listener.onClosing(socket, 1001, "Going away")
            call.until(12_000) { call.frames.count { it.has("setup") } >= 4 && call.sessions.size >= 2 }
            val setups = call.frames.filter { it.has("setup") }.map { it.getJSONObject("setup").optJSONObject("sessionResumption")?.optString("handle").orEmpty() }
            assertEquals(listOf("", "synthetic-handle-1", "synthetic-handle-1", ""), setups.take(4))
            assertTrue(call.failures.toString(), call.failures.isEmpty())
        } finally { call.close() }
    }

    // REVIEW-E4 P2-2 at the call: a sentence that starts like one of an earlier turn has its audio held while it matches, then played in
    // order with the rest; its caption comes whole.
    @Test(timeout = 15000) fun aNewSentenceThatStartsLikeAnEarlierOneLosesNoAudio() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            fun chunk(n: Int) = json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000",
                "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(480) { n.toByte() }))))))
            call.content(json("outputTranscription" to json("text" to "You have a meeting at 9.")));call.content(chunk(1))
            call.content(json("turnComplete" to true))
            call.content(json("outputTranscription" to json("text" to "You have a meeting")));call.content(chunk(2))
            call.content(json("outputTranscription" to json("text" to " at")));call.content(chunk(3))
            call.content(json("outputTranscription" to json("text" to " 2.")));call.content(chunk(4))
            call.content(json("turnComplete" to true))
            delay(200)
            assertEquals(listOf(1, 2, 3, 4), call.audio.map { it[0].toInt() })
            val said = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }
            assertEquals("You have a meeting at 9.You have a meeting at 2.", said)
        } finally { call.close() }
    }

    // REVIEW-E4 P3-3: the debug frame log keeps the non-audio frames only.
    @Test(timeout = 15000) fun theFrameLogKeepsNoAudioFrames() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            val before = call.voice.frameLog().size
            call.content(json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000",
                "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(480))))))))
            call.content(json("outputTranscription" to json("text" to "Hello.")))
            val added = call.voice.frameLog().drop(before)
            assertEquals(1, added.size);assertEquals("Hello.", added.single().getString("said"))
        } finally { call.close() }
    }

    // Device round 2, item 1: every muted call ended after ~8 minutes. The provider closed each connection for client inactivity; after the
    // fourth resume it no longer had the session, and each retry of the same handle was refused until the call dropped. A refused resume
    // now starts a new session from the conversation so far.
    @Test(timeout = 25000) fun aResumedSessionTheProviderNoLongerHasStartsAFreshOne() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.server(json("sessionResumptionUpdate" to json("newHandle" to "synthetic-handle-1", "resumable" to true)))
            call.refuseResumedSetups = 10
            val (socket, listener) = call.sessions.last()
            listener.onClosing(socket, 1008, "Session closed due to client inactivity.")
            call.until(10_000) { call.frames.count { it.has("setup") } >= 3 && call.sessions.size >= 2 }
            val setups = call.frames.filter { it.has("setup") }.map { it.getJSONObject("setup").optJSONObject("sessionResumption")?.optString("handle").orEmpty() }
            assertEquals("synthetic-handle-1", setups[1])
            assertEquals("the next one is a new session", "", setups[2])
            delay(1_000)
            assertTrue(call.failures.toString(), call.failures.isEmpty())
            assertTrue(call.events.any { it.optString("stage") == "provider_error" && it.optBoolean("resume_refused") })
        } finally { call.close() }
    }

    // Device round 2, item 1: Gemini ends a connection after ~2 minutes without a client message, and a muted microphone sends none. A short
    // silence goes out when the client has sent nothing for a while (never microphone data).
    @Test(timeout = 15000) fun whileTheClientSendsNothingAShortSilenceKeepsTheConnection() = runBlocking {
        val call = Call(v3())
        try {
            call.voice.keepAliveMs = 600
            call.start()
            call.until(6000) { call.frames.any { it.optJSONObject("realtimeInput")?.optJSONObject("audio") != null } }
            val audio = call.frames.mapNotNull { it.optJSONObject("realtimeInput")?.optJSONObject("audio") }.first()
            assertTrue(java.util.Base64.getDecoder().decode(audio.getString("data")).all { it == 0.toByte() })
        } finally { call.close() }
    }

    // Device round 2, item 3: an approval the owner answers within 4 s is not spoken; the model says only its result (round 2: the approvals
    // each started a turn of their own, and the model said they still needed approval after they had completed).
    @Test(timeout = 15000) fun anApprovalAnsweredQuicklyIsNotSpokenOnlyItsResult() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.hermes.decision = { id -> fixture("invoke_completed").put("request_id", id).put("tool_id", "c3".repeat(32)).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create").put("read_only", false) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until { call.events.any { it.optString("type") == "tool_approval" } }
            delay(1_500)
            call.voice.decideTool(call.requestId("f-2"), "proposal-synthetic-0000000000000000000000000000", "approve")
            call.until { call.told("f-2").isNotEmpty() }
            delay(500)
            assertEquals(listOf("completed"), call.told("f-2").map { it.getString("status") })
        } finally { call.close() }
    }

    // Device round 2, item 3: the model's notes ("*   **Analysis:** ...") came in its transcript. Speech never carries markdown bold: such a
    // piece is left out of the captions and the chat; its words around it stay.
    @Test(timeout = 15000) fun theModelsNotesNeverReachTheCaptions() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.content(json("outputTranscription" to json("text" to "Hermes is on it. ")))
            call.content(json("outputTranscription" to json("text" to "The chat will have it.  *   **Analysis:** The user is asking to search past conversations.")))
            call.content(json("outputTranscription" to json("text" to "---\n\nIn the meantime,")))
            call.content(json("turnComplete" to true))
            delay(200)
            val said = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }
            // REVIEW-E4 P3-5: the words before the mark in the same piece stay.
            assertEquals("Hermes is on it. The chat will have it.", said)
        } finally { call.close() }
    }

    // Device round 2, item 3: Gemini Live starts a turn for every spoken response; one frame of reads drew a turn per result and the answer
    // was said again. A result goes quietly (SILENT, the function closed) while another function of the same frame is still open; the last
    // one is spoken, with the others already in the model's context.
    @Test(timeout = 15000) fun aFrameOfReadsIsAnsweredWithOneSpokenTurn() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "session_search") Thread.sleep(800)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.firstSession()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "r-a", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "r-b", "name" to "session_search", "args" to json("query" to "invoices"))))).toString())
            call.until { call.responses("r-a").isNotEmpty() && call.responses("r-b").isNotEmpty() }
            // One release (device round 3): both results in one tool response, the last asks for the answer. Which call opened first, and so
            // comes first in the release, is up to the two calls' own coroutines.
            val release = call.toolResponses().single { rows -> rows.any { it.getString("id") == "r-a" } }
            assertEquals(setOf("r-a", "r-b"), release.map { it.getString("id") }.toSet())
            assertEquals(listOf("SILENT", "WHEN_IDLE"), release.map { it.getString("scheduling") })
            assertTrue(release.all { !it.getBoolean("willContinue") && it.getJSONObject("response").getString("status") == "completed" })
        } finally { call.close() }
    }

    // Device round 2, item 3: the model asked for a search again while the first was still running; that answer started a turn of its own.
    @Test(timeout = 15000) fun aRequestAskedAgainWhileTheFirstRunsIsAnsweredQuietly() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("r-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-1").isNotEmpty() }
            call.model("r-2", "notes_search", json("query" to "groceries"))
            call.until { call.responses("r-2").isNotEmpty() }
            val again = call.responses("r-2").single()
            assertEquals("already_requested", again.getJSONObject("response").getString("status"))
            assertEquals("SILENT", again.getString("scheduling"));assertFalse(again.getBoolean("willContinue"))
        } finally { call.close() }
    }

    // Device round 2, item 3: resultLost said "I'm having trouble reaching Hermes" once per waiting function, each in a turn of its own. One
    // outage, one spoken link state: every other function waiting on Hermes when it began learns it quietly. A question asked after the owner
    // heard of the outage is a new request: it hears on its own that it cannot reach Hermes now (REVIEW-E5-3 P2-1, Root's decision; before,
    // it learned it quietly too and nothing was spoken for it until Hermes came back).
    @Test(timeout = 20000) fun oneOutageIsSpokenOnceWhateverWaitsOnHermes() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("f-1").isNotEmpty() && call.told("w-1").isNotEmpty() }
            call.hermes.down = true
            call.until(6000) { call.responses("f-1").size >= 2 && call.responses("w-1").size >= 2 }
            call.model("f-2", "session_search", json("query" to "invoices"))
            call.until { call.responses("f-2").isNotEmpty() }
            delay(700)
            fun states(vararg ids: String) = ids.flatMap { id -> call.responses(id).filter { it.getJSONObject("response").optString("status") in setOf("hermes_unreachable", "lost_result") } }
            val waiting = states("f-1", "w-1")
            assertEquals(waiting.toString(), 2, waiting.size)
            assertEquals("one spoken link state for the functions waiting when the outage began", 1, waiting.count { it.getString("scheduling") == "WHEN_IDLE" })
            val asked = states("f-2")
            assertEquals("the question asked after the owner heard of the outage hears so on its own", listOf("WHEN_IDLE"), asked.map { it.getString("scheduling") })
            assertTrue("the call's own mark never reaches the model", (waiting + asked).none { it.getJSONObject("response").has(QUIET_STATE) })
        } finally { call.close() }
    }

    // Device round 2, item 3: the delay run said its result twice word for word, the second time in the turn of a following spoken answer.
    // The restored RepeatGuard holds a sentence said again before the owner speaks (its audio and its caption).
    @Test(timeout = 15000) fun aSentenceSaidAgainBeforeTheOwnerSpeaksIsHeldBack() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val line = "I searched your past conversations, but I couldn't find any mentions of invoices."
            call.content(json("outputTranscription" to json("text" to line)));call.content(json("turnComplete" to true))
            call.content(json("outputTranscription" to json("text" to line)));call.content(json("turnComplete" to true))
            delay(200)
            val said = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.map { it.optString("text") }.filter(String::isNotBlank)
            assertEquals(listOf(line), said)
        } finally { call.close() }
    }

    // REVIEW-E3-2 finding 1: an answer counts only in the model's reply to it. After the reply to "No thanks." ended, a call back sent in its
    // own frame before the next hand-over is refused (before: it passed on that earlier answer).
    @Test(timeout = 15000) fun aCallBackAfterTheReplyToTheAnswerEndedNeedsANewQuestion() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            call.model("w-1", "hermes_work", json("text" to "Draft the synthetic report."))
            call.until { call.told("w-1").isNotEmpty() }
            call.content(json("turnComplete" to true))                                  // the question's turn ends
            call.content(json("inputTranscription" to json("text" to "No thanks.")))   // the owner declines
            call.content(json("turnComplete" to true))                                  // the model's reply ("Understood") ends
            call.hermes.handover = { body -> Thread.sleep(800);fixture("handover_queued").put("request_id", body.getString("request_id")).put("callback_consent", "unset") }
            call.model("cb-2", "hermes_callback", json("enabled" to true))
            call.model("w-2", "hermes_work", json("text" to "Book a synthetic table."))
            call.until { call.told("cb-2").isNotEmpty() }
            delay(300)
            assertEquals("owner_has_not_answered", call.told("cb-2").single().getString("detail"))
            assertTrue(call.hermes.bodies("/callback").none { it.optBoolean("enabled", true) })
        } finally { call.close() }
    }

    // REVIEW-E3-2 finding 2 (the reviewer's proof E, inverted): a write and the same read in one frame: the read goes to Hermes, after the
    // frame dropped the reusable reads.
    @Test(timeout = 15000) fun aWriteAndTheSameReadInOneFrameAskHermesForTheRead() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                val write = body.optString("tool") == "t_hermes_notes_create"
                if (write) Thread.sleep(600)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")).apply { if (write) put("read_only", false).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create") })
            }
            call.start()
            call.model("r-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-1").isNotEmpty() }
            val (socket, listener) = call.firstSession()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "w-1", "name" to "t_hermes_notes_create", "args" to json("title" to "Synthetic groceries note")))
                .put(json("id" to "r-2", "name" to "notes_search", "args" to json("query" to "groceries"))))).toString())
            call.until { call.told("r-2").isNotEmpty() && call.told("w-1").isNotEmpty() }
            assertFalse(call.told("r-2").single().optBoolean("repeated_request"))
            assertEquals(3, call.hermes.bodies("/tools/invoke").size)
        } finally { call.close() }
    }

    // REVIEW-E3 finding 2 (the reviewer's proof C, inverted): a read after a write in the same call goes to Hermes again.
    @Test(timeout = 15000) fun aReadAfterAWriteIsAskedOfHermesAgain() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                val write = body.optString("tool") == "t_hermes_notes_create"
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")).apply { if (write) put("read_only", false).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create") })
            }
            call.start()
            call.model("r-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-1").isNotEmpty() }
            call.model("w-1", "t_hermes_notes_create", json("title" to "Synthetic groceries note"))
            call.until { call.told("w-1").isNotEmpty() }
            call.model("r-2", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-2").isNotEmpty() }
            assertEquals("the second read reached Hermes", 3, call.hermes.bodies("/tools/invoke").size)
            assertFalse(call.told("r-2").single().optBoolean("repeated_request"))
        } finally { call.close() }
    }

    // REVIEW-E3 finding 2: the reuse covers a repeat moments later only (10 s), not a "check again" a while after.
    @Test(timeout = 25000) fun aReadRepeatedAfterTenSecondsIsAskedOfHermesAgain() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("r-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-1").isNotEmpty() }
            delay(10_300)
            call.model("r-2", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-2").isNotEmpty() }
            assertEquals(2, call.hermes.bodies("/tools/invoke").size)
        } finally { call.close() }
    }

    // Device round 1, item 3: after an outage a state and the result came in a burst, and Gemini Live gave each spoken response a turn
    // of its own: an invented answer for the state, then the real one. A spoken state the result replaces within 500 ms is never sent.
    @Test(timeout = 15000) fun aStateTheResultReplacesAtOnceIsNeverSaid() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.hermes.decision = { id -> fixture("invoke_completed").put("request_id", id).put("tool_id", "c3".repeat(32)).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create").put("read_only", false) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until { call.events.any { it.optString("type") == "tool_approval" } }
            // Approved at once (the device test approved 0.4 s after the card): the result replaces the waiting state.
            call.voice.decideTool(call.requestId("f-2"), "proposal-synthetic-0000000000000000000000000000", "approve")
            call.until { call.told("f-2").isNotEmpty() }
            delay(800)
            assertEquals(listOf("completed"), call.told("f-2").map { it.getString("status") })
        } finally { call.close() }
    }

    // Device round 1, item 4: an outage longer than the provider's reconnect ended the call ("The call dropped"). A reconnect now goes on
    // with the context the call verified, and the result owed comes once Hermes is back.
    @Test(timeout = 40000) fun aCallRidesOutAHermesOutageAcrossProviderReconnects() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.hermes.down = true
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until(6000) { call.told("f-1").any { it.optString("status") == "hermes_unreachable" } }
            repeat(3) {
                val setups = call.frames.count { it.has("setup") }
                val (socket, listener) = call.sessions.last()
                listener.onClosing(socket, 1000, "Synthetic provider reconnect")
                call.until(6000) { call.frames.count { it.has("setup") } > setups }
            }
            assertTrue(call.failures.toString(), call.failures.isEmpty())
            call.hermes.down = false
            call.until(10_000) { call.frames.any { it.toString().contains("Verified earlier notes_search tool outcome") && it.toString().contains("completed") } }
            assertTrue(call.failures.toString(), call.failures.isEmpty())
        } finally { call.close() }
    }

    // Device round 1, item 4: the upper bound. Hermes out of reach for the whole limit ends the call with the "Hermes didn't answer" line.
    @Test(timeout = 15000) fun aHermesOutageEndsTheCallOnlyAfterItsLimit() = runBlocking {
        val call = Call(v3())
        try {
            call.voice.outageLimitMs = 1500
            call.start()
            call.hermes.down = true
            call.until(8000) { call.failures.isNotEmpty() }
            // REVIEW-E3 finding 4: the phone could not get through, so the line says that, not that Hermes did not answer.
            assertEquals(listOf(dev.hermes.android.data.APP_CONNECTION_ERROR), call.failures.toList())
        } finally { call.close() }
    }

    // Device round 1, item 6: while an approval waited, the model was told the link was lost but the call's tool log had nothing for
    // 47.6 s. The state reaches the log within 5 s; the card and its chip stay as they are.
    @Test(timeout = 15000) fun aLinkStateDuringAnApprovalReachesTheToolLogWithinFiveSeconds() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until(7000) { call.told("f-2").isNotEmpty() }
            val approval = call.events.first { it.optString("type") == "tool_approval" }
            val lost = System.currentTimeMillis()
            call.hermes.down = true
            call.until(5000) { call.events.any { it.optString("type") == "tool_receipt" && it.optString("status") == "hermes_unreachable" } }
            assertTrue(System.currentTimeMillis() - lost < 5000)
            val logged = call.events.last { it.optString("type") == "tool_receipt" && it.optString("status") == "hermes_unreachable" }
            assertTrue(logged.getBoolean("log_only"))
            assertEquals("hermes_unreachable", call.told("f-2").last().getString("status"))
            val cards = pendingCallToolApprovals(emptyList(), approval)
            assertEquals("the card stays", cards, pendingCallToolApprovals(cards, logged))
        } finally { call.close() }
    }

    // Device round 1, item 7: a function call refused on the phone before it reached Hermes says what was wrong, to the model and in the
    // call's tool log, so the model can fix it in one retry and the log shows why.
    @Test(timeout = 15000) fun aRefusedArgumentNamesItsProblem() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("m-1", "hermes_memory", JSONObject())
            call.until { call.told("m-1").isNotEmpty() }
            val told = call.told("m-1").single()
            assertEquals("invalid_arguments", told.getString("detail"))
            assertEquals("hermes_memory takes exactly one argument, query", told.getString("problem"))
            assertEquals("invalid_arguments", call.events.last { it.optString("type") == "tool_receipt" && it.optString("request_id") == call.requestId("m-1") }.getString("detail"))
        } finally { call.close() }
    }

    // Device round 1, item 8: the model asked for the same search 3 s after its result and a second card waited for nobody. A read this
    // call completed with the same arguments moments ago is answered with that result.
    @Test(timeout = 15000) fun theSameReadAskedAgainMomentsLaterIsAnsweredWithItsResult() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            call.model("f-3", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-3").isNotEmpty() }
            assertEquals("one request to Hermes", 1, call.hermes.bodies("/tools/invoke").size)
            val again = call.told("f-3").single()
            assertEquals("completed", again.getString("status"));assertTrue(again.getBoolean("repeated_request"))
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aRefusedOrUnconfirmedHandOverIsSaidAsItIs() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.handover = { body -> fixture(if (body.getString("text").startsWith("Full")) "handover_not_sent" else "handover_uncertain").put("request_id", body.getString("request_id")) }
            call.start()
            call.model("w-1", "hermes_work", json("text" to "Full queue request"))
            call.model("w-2", "hermes_work", json("text" to "Unconfirmed request"))
            call.until { call.told("w-1").isNotEmpty() && call.told("w-2").isNotEmpty() }
            val refused = call.told("w-1").single()
            assertEquals("failed", refused.getString("status"));assertEquals("queue_full", refused.getString("detail"))
            assertEquals(false, refused.get("ran_on_hermes"));assertFalse(refused.getBoolean("will_update"))
            val unknown = call.told("w-2").single()
            assertEquals("uncertain", unknown.getString("status"));assertEquals("admission_unconfirmed", unknown.getString("detail"))
            assertFalse(unknown.getBoolean("will_update"))
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aHandOverConflictIsAFailedRequestThatIsNeverResent() = runBlocking {
        // Lane A: the same request id with other words is 409 request_conflict (contract sections 2 and 4); Hermes keeps the earlier job.
        val call = Call(v3())
        try {
            call.hermes.handoverCode = 409
            call.hermes.handover = { fixture("request_error_call_closed").put("detail", "Synthetic conflict").put("error_code", "request_conflict") }
            call.start()
            call.model("w-9", "hermes_work", json("text" to "Synthetic task."))
            call.until { call.told("w-9").isNotEmpty() }
            val told = call.told("w-9").single()
            assertEquals("failed", told.getString("status"));assertEquals("http_409", told.getString("detail"));assertEquals(false, told.get("ran_on_hermes"))
            delay(700)
            assertEquals("Hermes' own refusal is never resent", 1, call.hermes.bodies("/handover").size)
        } finally { call.close() }
    }

    // Device round 1, item 3: a lost answer that the resend brings back within the settle time is no news: only the result is said.
    @Test(timeout = 15000) fun aLossOverBeforeItsStateWouldBeSaidIsNotSaid() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, n ->
                if (n == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").any { it.optString("status") == "completed" } }
            delay(700)
            assertEquals(listOf("completed"), call.told("f-1").map { it.getString("status") })
        } finally { call.close() }
    }

    // Hermes is out of reach for 2.5 s (every request loses its connection, the updates poll too), longer than a lost state waits (1.5 s).
    // Follow-up 16: it was 1.2 s, which only outlasted the settle while a retry slept out its backoff; with retries going again as the link
    // returns (device round 4, D1) a 1.2 s outage is never told, as LINK-121 (b) means.
    @Test(timeout = 15000) fun aLostAnswerIsAStateThenTheSameRequestIdBringsTheResultOnce() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.hermes.down = true
            call.model("f-1", "notes_search", json("query" to "groceries"))
            delay(2500)
            call.hermes.down = false
            call.until { call.told("f-1").size >= 2 }
            delay(300)
            assertEquals(listOf("hermes_unreachable", "completed"), call.told("f-1").map { it.getString("status") })
            val lost = call.told("f-1").first()
            assertEquals("connection_lost", lost.getString("detail"));assertTrue(lost.getBoolean("retry_safe"));assertTrue(lost.getBoolean("will_update"))
            val sent = call.hermes.bodies("/tools/invoke")
            assertTrue(sent.size >= 2);assertEquals("The resend is the same request", 1, sent.map { it.getString("request_id") }.toSet().size)
            timelineSafe(call)
            assertTrue("The tool window hears the link state", call.events.any { it.optString("type") == "tool_receipt" && it.optString("status") == "hermes_unreachable" })
            val link = call.events.filter { it.optString("type") == "hermes_link" }.map { it.getBoolean("reachable") }
            // An updates poll already in flight when Hermes went away may answer first, so the last two say it.
            assertEquals("The card and chip say Can't reach Hermes, then stop once it answers", listOf(false, true), link.takeLast(2))
        } finally { call.close() }
    }

    // INT-121 follow-up 14 (Root's rule): after an outage the owner heard ("can't reach Hermes"), the link's return is one spoken turn per
    // frame at most. A frame of Hermes reads that Hermes answered "running"; at 2 s Hermes is out of reach (the updates poll loses its
    // connection), the lost link is told, and at 8 s Hermes is back: the call reads every receipt again (recovered). [done] read again as
    // completed; [byUpdates]: their results come in the updates page that brings the link back. The spoken releases from the return on.
    private suspend fun turnsAtReturn(ids: List<String>, done: Set<String> = emptySet(), byUpdates: Boolean = false): List<List<String>> {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.hermes.status = { rid -> fixture(if (done.any { call.requestId(it) == rid }) "invoke_completed" else "invoke_running").put("request_id", rid) }
            call.start()
            val (socket, listener) = call.sessions.last()
            val asked = System.currentTimeMillis()
            val calls = JSONArray().apply { for (id in ids) put(json("id" to id, "name" to "notes_search", "args" to json("query" to "synthetic $id"))) }
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to calls)).toString())
            delay((asked + 2_000 - System.currentTimeMillis()).coerceAtLeast(0))
            call.hermes.down = true
            call.until(6_000) { call.prompts().any { it.getJSONObject("response").optString("status") in setOf("lost_result", "hermes_unreachable") } }
            delay((asked + 8_000 - System.currentTimeMillis()).coerceAtLeast(0))
            val before = call.toolResponses().size
            if (byUpdates) for (id in done) {
                val completed = fixture("updates_page").getJSONArray("events").getJSONObject(2).put("request_id", call.requestId(id))
                call.hermes.pages += JSONObject(fixture("updates_page").toString()).put("events", JSONArray().put(completed)).put("closed", false)
            }
            val linkEvents = call.events.size
            call.hermes.down = false
            // From the link's return (the updates poll backs off, so it comes 1.7-2.0 s after Hermes), past the return's gather.
            call.until(6_000) { call.events.drop(linkEvents).any { it.optString("type") == "hermes_link" && it.optBoolean("reachable") } }
            delay(CALL_NOTICE_MS + 1_500)
            return call.toolResponses().drop(before).filter { rows -> rows.any { it.optString("scheduling") == "WHEN_IDLE" } }
                .map { rows -> rows.map { "${it.getString("id")}:${it.getJSONObject("response").optString("status")}" }.sorted() }
        } finally { call.close() }
    }

    // Follow-up 14 (i): a frame of three reads still running at the return is one spoken turn, "still running" for all three.
    @Test(timeout = 40000) fun afterAToldOutageAFrameStillRunningIsOneTurnAtTheReturn() = runBlocking {
        assertEquals(listOf(listOf("a:still_running", "b:still_running", "c:still_running")), turnsAtReturn(listOf("a", "b", "c")))
    }

    // Follow-up 14 (ii): a's result is in at the return, read again or in the updates page that brings the link back: one spoken turn, a's
    // result with "still running" for the others, and no "still running" for a before its result.
    @Test(timeout = 60000) fun afterAToldOutageAResultInAtTheReturnAnswersWithItsFrameInOneTurn() = runBlocking {
        val turns = listOf(false, true).associateWith { byUpdates -> turnsAtReturn(listOf("a", "b", "c"), done = setOf("a"), byUpdates = byUpdates) }
        val one = listOf(listOf("a:completed", "b:still_running", "c:still_running"))
        assertEquals(mapOf(false to one, true to one), turns)
    }

    // Follow-up 14 (iii): a single call behaves as a frame of three: one spoken turn, "still running".
    @Test(timeout = 40000) fun afterAToldOutageASingleCallIsOneTurnAtTheReturn() = runBlocking {
        assertEquals(listOf(listOf("a:still_running")), turnsAtReturn(listOf("a")))
    }

    /**
     * Contract sections 6 and 8: the timeline a 1.2.0 plugin takes knows only its own statuses and error codes. The call engine's
     * tool_receipt events carry those, or one of the three link states the call service maps to them (running, unavailable +
     * network, uncertain + timeout), and the phone's own states carry no error code at all.
     */
    private fun timelineSafe(call: Call) {
        val v2 = setOf("ready", "running", "needs_approval", "transferred", "completed", "failed", "denied", "expired", "uncertain", "cancelled", "unavailable", "not_sent", "submission_unconfirmed")
        val mapped = setOf("still_running", "hermes_unreachable", "lost_result")
        val codes = Regex("http_[45][0-9]{2}|timeout|dns|tls|network|invalid_response|tool_http_failure|admission_rejected|memory_lookup_busy|memory_lookup_timeout|memory_lookup_failed|hermes_updating")
        for (event in call.events.filter { it.optString("type") == "tool_receipt" }) {
            val status = event.getString("status")
            assertTrue("$status is a 1.2.0 status or a mapped link state", status in v2 || status in mapped)
            if (status in mapped) assertFalse("A link state carries no error code of its own", event.has("error_code"))
            else if (event.has("error_code") && !event.has("tool")) assertTrue(event.getString("error_code"), codes.matches(event.getString("error_code")))
        }
    }

    @Test(timeout = 15000) fun aProviderSendingTheSameCallAgainNeverRunsItTwice() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            call.model("f-1", "notes_search", json("query" to "groceries"))
            delay(500)
            assertEquals(1, call.hermes.bodies("/tools/invoke").size)
            assertEquals("Nothing follows a final result for the same call", 1, call.told("f-1").size)
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun anOpenCallSentAgainIsNotToldItsStateTwice() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("r-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("r-1").isNotEmpty() }
            call.model("r-1", "notes_search", json("query" to "groceries"))
            delay(500)
            assertEquals(1, call.hermes.bodies("/tools/invoke").size)
            assertEquals("The same state is never sent twice in a row (contract section 6)", 1, call.told("r-1").size)
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aToolNotDeclaredOrABadArgumentNeverReachesHermes() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.model("x-1", "delete_everything", json())
            call.model("x-2", "invoke_tool", json("tool_id" to "notes_search", "arguments" to "not a JSON object"))
            call.until { call.told("x-1").isNotEmpty() && call.told("x-2").isNotEmpty() }
            assertEquals("tool_unknown", call.told("x-1").single().getString("detail"))
            assertEquals("invalid_arguments", call.told("x-2").single().getString("detail"))
            assertTrue(call.hermes.bodies("/tools/invoke").isEmpty())
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aPluginWithoutTheV3ContractGetsThe120Requests() = runBlocking {
        val call = Call(v2())
        try {
            var reads = 0
            call.hermes.invoke = { body, _ -> call.hermes.ok(JSONObject().put("request_id", body.getString("request_id")).put("tool_id", "b".repeat(64)).put("tool_name", "notes_search").put("status", "running")) }
            call.hermes.status = { id -> reads++;JSONObject().put("request_id", id).put("tool_id", "b".repeat(64)).put("tool_name", "notes_search").put("status", "completed").put("result", JSONObject().put("ok", true)) }
            call.start()
            call.model("v-1", "notes_search", json("query" to "groceries"))
            call.model("v-2", "invoke_tool", json("tool_id" to "b".repeat(64), "arguments" to "{\"query\":\"groceries\"}"))
            call.until(6000) { call.told("v-1").isNotEmpty() && call.told("v-2").size >= 2 }
            assertEquals("A name the plugin did not declare is not a tool here", "tool_unknown", call.told("v-1").single().getString("detail"))
            assertEquals(listOf("running", "completed"), call.told("v-2").map { it.getString("status") })
            assertEquals("A 1.2.0 receipt that is only just running is quiet", "SILENT", call.responses("v-2").first().getString("scheduling"))
            val sent = call.hermes.bodies("/tools/invoke").single()
            assertEquals(setOf("request_id", "tool_id", "arguments"), sent.keys().asSequence().toSet() - "approval")
            assertEquals("b".repeat(64), sent.getString("tool_id"))
            assertTrue("The 1.2.0 status read brought the result", reads >= 1)
            assertTrue("No updates channel on a 1.2.0 plugin", call.hermes.paths().none { it.endsWith("/updates") })
            timelineSafe(call)
        } finally { call.close() }
    }

    // REVIEW-E4-2 proofS3, inverted: the provider cancels the frame's last function (the owner barged in); the ready result is still spoken.
    @Test(timeout = 20000) fun aFrameWhoseLastFunctionIsCancelledIsStillSpoken() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "session_search") Thread.sleep(600)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            call.server(json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "fast", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "slow", "name" to "session_search", "args" to json("query" to "invoices"))))))
            delay(300)
            call.server(json("toolCallCancellation" to json("ids" to JSONArray().put("slow"))))
            delay(2_000)
            assertEquals("WHEN_IDLE", call.responses("fast").single().getString("scheduling"))
            assertTrue("a cancelled function is never answered", call.responses("slow").isEmpty())
        } finally { call.close() }
    }

    // REVIEW-E4-2 proofH, inverted: a turn that opens like an earlier sentence plays its first audio at once; a real repeat is still held
    // from where its match is a few words long, and dropped.
    @Test(timeout = 15000) fun aTurnThatStartsLikeAnEarlierSentencePlaysItsFirstAudioAtOnce() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            fun said(text: String, n: Int) = json("outputTranscription" to json("text" to text), "modelTurn" to json("parts" to JSONArray().put(
                json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(480) { n.toByte() }))))))
            call.content(said("I found one mention of your invoice.", 1))
            call.content(json("turnComplete" to true))
            call.content(said("I found", 2))
            delay(400)
            assertEquals("the new turn's first audio plays at once", listOf(1, 2), call.audio.map { it[0].toInt() })
            call.content(said(" three notes.", 3))
            delay(200)
            assertEquals(listOf(1, 2, 3), call.audio.map { it[0].toInt() })
            call.content(json("turnComplete" to true))
            call.content(said("I found", 4))
            call.content(said(" one mention of your", 5))
            call.content(json("outputTranscription" to json("text" to " invoice.")))
            call.content(json("turnComplete" to true))
            delay(200)
            // REVIEW-E5 P2-4: the guard never holds audio; the repeat is kept out of the captions only.
            assertEquals("every piece of audio plays as it comes", listOf(1, 2, 3, 4, 5), call.audio.map { it[0].toInt() })
        } finally { call.close() }
    }

    // Device round 3, F3 (c-readsFrame-1): a frame of three reads was answered one result at a time, and the model said the same answer four
    // times. Reads that all answer before the notice go in one tool response, and the model is asked to answer once.
    @Test(timeout = 20000) fun aFrameOfReadsIsAnsweredInOneToolResponse() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                when (body.optJSONObject("arguments")?.optString("query")) { "receipts" -> Thread.sleep(1_200);"invoices" -> Thread.sleep(2_200) }
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "a", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "b", "name" to "notes_search", "args" to json("query" to "receipts")))
                .put(json("id" to "c", "name" to "session_search", "args" to json("query" to "invoices"))))).toString())
            call.until(6000) { listOf("a", "b", "c").all { call.responses(it).isNotEmpty() } }
            delay(800)
            val releases = call.toolResponses().filter { rows -> rows.any { it.getString("id") in setOf("a", "b", "c") } }
            assertEquals("one tool response for the frame", 1, releases.size)
            assertEquals(listOf("SILENT", "SILENT", "WHEN_IDLE"), releases.single().map { it.getString("scheduling") })
            assertEquals("the model is asked to answer once", 1, call.prompts().size)
        } finally { call.close() }
    }

    // Device round 3, F7 (d-readRemind-1): the calendar's result started a turn while the reminder the model had just asked for was still
    // running, and the model said "I've also set your reminder" before the reminder's failure came. A result waits while another call the
    // model made is still running, and both go in one tool response.
    @Test(timeout = 20000) fun noResultStartsATurnWhileAnotherCallTheModelMadeIsStillRunning() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                val write = body.optString("tool") == "t_hermes_notes_create"
                Thread.sleep(if (write) 900 else 200)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")).apply { if (write) put("read_only", false).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create") })
            }
            call.start()
            call.model("read", "notes_search", json("query" to "groceries"))
            delay(100)
            call.model("write", "t_hermes_notes_create", json("title" to "Synthetic reminder"))
            call.until(6000) { call.responses("read").isNotEmpty() && call.responses("write").isNotEmpty() }
            delay(500)
            val release = call.toolResponses().single { rows -> rows.any { it.getString("id") == "read" } }
            assertEquals("the read's result waited for the write's", listOf("read", "write"), release.map { it.getString("id") })
            assertEquals(listOf("SILENT", "WHEN_IDLE"), release.map { it.getString("scheduling") })
            assertEquals(1, call.prompts().size)
        } finally { call.close() }
    }

    // Device round 3, F4 (d-raw-2): "change my favourite colour to green, then look it up" made one frame [write, read]; the read answered
    // first with the old value. A call after a write in its frame runs once the write has its result.
    @Test(timeout = 20000) fun aReadAfterAWriteInItsFrameRunsAfterTheWritesResult() = runBlocking {
        val call = Call(v3())
        try {
            val writeDone = AtomicLong(0);val readStarted = AtomicLong(0)
            call.hermes.invoke = { body, _ ->
                val write = body.optString("tool") == "t_hermes_notes_create"
                if (write) { Thread.sleep(600);writeDone.set(System.nanoTime()) } else readStarted.compareAndSet(0, System.nanoTime())
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")).apply { if (write) put("read_only", false).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create") })
            }
            call.start()
            val (socket, listener) = call.firstSession()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "w-1", "name" to "t_hermes_notes_create", "args" to json("title" to "Favourite colour green")))
                .put(json("id" to "r-1", "name" to "notes_search", "args" to json("query" to "favourite colour"))))).toString())
            call.until(6000) { call.told("r-1").isNotEmpty() && call.told("w-1").isNotEmpty() }
            assertTrue("the read reached Hermes after the write's result", readStarted.get() >= writeDone.get() && writeDone.get() > 0)
            val release = call.toolResponses().single { rows -> rows.any { it.getString("id") == "r-1" } }
            assertEquals("both results in one tool response", setOf("w-1", "r-1"), release.map { it.getString("id") }.toSet())
        } finally { call.close() }
    }

    // Device round 3, F2 (c-resultLost-3r): an approval tapped 0.7 s into an outage never reached Hermes, and the model was told lost_result
    // for a decision Hermes never got. The tap is kept and sent once Hermes is back; the model never hears lost_result for it.
    @Test(timeout = 25000) fun anApprovalTappedWhileHermesIsDownIsSentWhenItIsBack() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.hermes.decision = { id -> fixture("invoke_completed").put("request_id", id).put("tool_id", "c3".repeat(32)).put("tool", "t_hermes_notes_create").put("tool_name", "t_hermes_notes_create").put("read_only", false) }
            call.hermes.status = { id -> fixture("invoke_needs_approval").put("request_id", id) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until(5000) { call.events.any { it.optString("type") == "tool_approval" } }
            call.hermes.down = true
            val decided = call.scope.async { runCatching { call.voice.decideTool(call.requestId("f-2"), "proposal-synthetic-0000000000000000000000000000", "approve") } }
            delay(2_500)
            assertFalse("still waiting for Hermes", decided.isCompleted)
            call.hermes.down = false
            val outcome = withTimeout(10_000) { decided.await() }
            assertTrue("the decision was sent: ${outcome.exceptionOrNull()}", outcome.isSuccess)
            call.until(6000) { call.told("f-2").any { it.getString("status") == "completed" } }
            assertFalse("never lost_result for a decision Hermes never got", call.told("f-2").any { it.getString("status") == "lost_result" })
        } finally { call.close() }
    }

    // Device round 3, F2: the same approval was said again after an outage ("I'm still waiting for your approval" twice). Hermes saying it
    // again joins the model's context quietly: one spoken approval per approval.
    @Test(timeout = 30000) fun anApprovalIsSpokenOnceThoughHermesSaysItAgainAfterAnOutage() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id"))) }
            call.hermes.status = { id -> fixture("invoke_needs_approval").put("request_id", id) }
            call.start()
            call.model("f-2", "t_hermes_notes_create", json("title" to "Synthetic note"))
            call.until(8000) { call.told("f-2").any { it.getString("status") == "needs_approval" } }
            call.hermes.down = true
            call.until(10_000) { call.told("f-2").any { it.getString("status") == "hermes_unreachable" } }
            call.hermes.down = false
            call.until(10_000) { call.told("f-2").count { it.getString("status") == "needs_approval" } >= 2 }
            delay(5_000)
            val approvals = call.responses("f-2").filter { it.getJSONObject("response").getString("status") == "needs_approval" }
            assertEquals("said once, told again quietly", listOf("WHEN_IDLE", "SILENT"), approvals.map { it.getString("scheduling") })
        } finally { call.close() }
    }

    // REVIEW-E5 P2-4 (proofA, inverted; Root's decision): the guard never holds, delays or drops audio. A sentence that only starts like an
    // earlier one, with 11 s of audio before its words go on, is heard at once and in full, and its words are in the captions; a repeat is
    // kept out of the captions only.
    @Test(timeout = 15000) fun theRepeatGuardNeverHoldsOrDropsAudio() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            fun pcm(n: Int, size: Int) = json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000",
                "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(size) { n.toByte() })))))
            call.content(json("outputTranscription" to json("text" to "I am still waiting for your approval on the screen."), "modelTurn" to pcm(1, 4800)))
            call.content(json("turnComplete" to true))
            call.content(json("outputTranscription" to json("text" to "I am still waiting for"), "modelTurn" to pcm(2, 4800)))
            val before = call.audio.size
            repeat(11) { call.content(json("modelTurn" to pcm(3, 48_000))) }
            assertEquals("the audio is played as it comes", before + 11, call.audio.size)
            call.content(json("outputTranscription" to json("text" to " your answer before I book the flight."), "modelTurn" to pcm(4, 4800)))
            call.content(json("outputTranscription" to json("text" to " Here is the list."), "modelTurn" to pcm(5, 4800)))
            call.content(json("turnComplete" to true))
            delay(300)
            assertEquals(listOf(1, 2, 3, 4, 5), call.audio.map { it[0].toInt() }.distinct())
            val said = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }
            assertTrue("the sentence that was no repeat is in the captions: $said", said.contains("I am still waiting for your answer before I book the flight."))
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aRepeatIsKeptOutOfTheCaptionsButItsAudioPlays() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            fun pcm(n: Int, size: Int) = json("inlineData" to json("mimeType" to "audio/pcm;rate=24000", "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(size) { n.toByte() })))
            fun said(text: String, n: Int) = json("outputTranscription" to json("text" to text), "modelTurn" to json("parts" to JSONArray().put(pcm(n, 480))))
            call.content(said("I'm still waiting for your approval on the screen.", 1))
            call.content(json("turnComplete" to true))
            call.content(said("I'm still waiting for your approval", 2))
            repeat(3) { call.content(json("modelTurn" to json("parts" to JSONArray().put(pcm(3, 192_000))))) }
            call.content(json("outputTranscription" to json("text" to " on the screen.")))
            call.content(json("turnComplete" to true))
            delay(300)
            assertEquals("all of it is heard, in order", listOf(1, 2, 3), call.audio.map { it[0].toInt() }.distinct())
            val said = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }
            assertEquals("the repeat is written once", "I'm still waiting for your approval on the screen.", said)
        } finally { call.close() }
    }

    // REVIEW-E5 P2-1 (proofN, inverted): a frame of slow reads gets one "still running" turn: every call near its notice time is told in the
    // same release.
    @Test(timeout = 25000) fun aFrameOfSlowReadsGetsOneStillRunningTurn() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> Thread.sleep(6_000);call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id"))) }
            call.start()
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "a", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "b", "name" to "notes_search", "args" to json("query" to "receipts")))
                .put(json("id" to "c", "name" to "session_search", "args" to json("query" to "invoices"))))).toString())
            delay(4_500)
            val notices = call.toolResponses().filter { rows -> rows.any { it.getJSONObject("response").optString("status") == "still_running" } }
            assertEquals("one release", 1, notices.size)
            assertEquals(setOf("a", "b", "c"), notices.single().map { it.getString("id") }.toSet())
            assertEquals("one prompt", 1, call.prompts().size)
        } finally { call.close() }
    }

    // REVIEW-E5 P2-2 (proofH, inverted): a call already told "still running" holds nothing: a later, unrelated ready answer goes at once.
    @Test(timeout = 30000) fun aCallToldStillRunningHoldsNoLaterAnswer() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "session_search") Thread.sleep(7_000)
                call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            call.model("slow", "session_search", json("query" to "invoices"))
            call.until(6_000) { call.prompts().any { it.getString("id") == "slow" } }
            val asked = System.currentTimeMillis()
            call.model("fast", "notes_search", json("query" to "groceries"))
            call.until(4_000) { call.responses("fast").isNotEmpty() }
            val waited = System.currentTimeMillis() - asked
            assertTrue("the ready answer waited $waited ms", waited < 1_500)
            assertEquals("WHEN_IDLE", call.responses("fast").single().getString("scheduling"))
            assertFalse("the slow call was still running", call.told("slow").any { it.getString("status") == "completed" })
            call.until(8_000) { call.told("slow").any { it.getString("status") == "completed" } }
        } finally { call.close() }
    }

    // REVIEW-E5 P2-3 (proofW, inverted): a call whose approval is still settling holds nothing: the ready read is answered at once, and the
    // approval is said after its settle because it is still open.
    @Test(timeout = 20000) fun aReadyReadIsAnsweredAtOnceNextToAnApprovalStillSettling() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "t_hermes_notes_create") call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id")))
                else call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            val asked = System.currentTimeMillis()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "r-1", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "w-1", "name" to "t_hermes_notes_create", "args" to json("title" to "Synthetic note"))))).toString())
            call.until(3_000) { call.responses("r-1").isNotEmpty() }
            val answered = System.currentTimeMillis() - asked
            assertTrue("the ready read waited $answered ms", answered < 1_500)
            assertEquals("WHEN_IDLE", call.responses("r-1").single().getString("scheduling"))
            call.until(7_000) { call.responses("w-1").isNotEmpty() }
            assertEquals("needs_approval", call.told("w-1").single().getString("status"))
            assertEquals("WHEN_IDLE", call.responses("w-1").single().getString("scheduling"))
        } finally { call.close() }
    }

    // REVIEW-E5 P3-1: a release is built as it is sent: a function the provider cancelled while its release waited in the queue (the writer
    // stalled) is left out, so a cancelled call is never answered.
    @Test(timeout = 20000) fun aCallCancelledWhileItsReleaseWaitsIsNotAnswered() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            val gate = java.util.concurrent.CountDownLatch(1)
            call.holdToolResponses = gate
            call.model("a", "notes_search", json("query" to "groceries"))
            call.until { call.responses("a").isNotEmpty() }
            call.model("c", "notes_search", json("query" to "receipts"))
            delay(1_000)
            call.server(json("toolCallCancellation" to json("ids" to JSONArray().put("c"))))
            delay(200)
            call.holdToolResponses = null;gate.countDown()
            delay(1_500)
            assertTrue("the cancelled call was answered: ${call.responses("c")}", call.responses("c").isEmpty())
        } finally { call.close() }
    }

    // REVIEW-E5 P3-2: a read waiting for an approval-needing write of its frame gets no "still running" of its own; the write's approval is
    // what the owner hears.
    @Test(timeout = 20000) fun aCallWaitingOnItsFramesWriteGetsNoNoticeOfItsOwn() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "t_hermes_notes_create") call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id")))
                else call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "w-1", "name" to "t_hermes_notes_create", "args" to json("title" to "Synthetic note")))
                .put(json("id" to "r-1", "name" to "notes_search", "args" to json("query" to "notes"))))).toString())
            call.until(7_000) { call.responses("w-1").isNotEmpty() }
            delay(500)
            assertEquals("needs_approval", call.told("w-1").single().getString("status"))
            assertTrue("the waiting read was told: ${call.told("r-1")}", call.told("r-1").isEmpty())
        } finally { call.close() }
    }

    // E5 follow-up (the engine): three reads of one frame that Hermes answers at about 6.0, 6.2 and 6.5 s (their results come by the
    // updates channel, as Hermes sends them) are one "still running" turn and then one answer: two prompts in all.
    @Test(timeout = 25000) fun aSlowFrameOfReadsIsOneStillRunningTurnThenOneAnswer() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            val (socket, listener) = call.sessions.last()
            val asked = System.currentTimeMillis()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "a", "name" to "notes_search", "args" to json("query" to "groceries")))
                .put(json("id" to "b", "name" to "notes_search", "args" to json("query" to "receipts")))
                .put(json("id" to "c", "name" to "notes_search", "args" to json("query" to "invoices"))))).toString())
            for ((id, at) in listOf("a" to 6_000L, "b" to 6_200L, "c" to 6_500L)) {
                delay((asked + at - System.currentTimeMillis()).coerceAtLeast(0))
                val completed = fixture("updates_page").getJSONArray("events").getJSONObject(2).put("request_id", call.requestId(id))
                call.hermes.pages += JSONObject(fixture("updates_page").toString()).put("events", JSONArray().put(completed)).put("closed", false)
            }
            call.until(5_000) { listOf("a", "b", "c").all { id -> call.told(id).any { it.getString("status") == "completed" } } }
            delay(1_500)
            val notices = call.toolResponses().filter { rows -> rows.any { it.getJSONObject("response").optString("status") == "still_running" } }
            assertEquals("one notice for the frame", 1, notices.size)
            val answers = call.toolResponses().filter { rows -> rows.any { it.getJSONObject("response").optString("status") == "completed" } }
            assertEquals("one answer for the frame", 1, answers.size)
            assertEquals(setOf("a", "b", "c"), answers.single().map { it.getString("id") }.toSet())
            assertEquals("two prompts in all", 2, call.prompts().size)
        } finally { call.close() }
    }

    // LINK-121-CAUSE wiring: a lost link's tool receipt carries its cause, as the plugin's category, to the call's timeline when this call's
    // context says its plugin keeps it, and not without it (an older plugin would refuse the whole event batch).
    @Test(timeout = 30000) fun aLostLinksReceiptCarriesItsCauseToTheTimelineOnlyWhenThePluginKeepsIt() = runBlocking {
        for (keeps in listOf(true, false)) {
            val call = Call(v3().apply { getJSONObject("call_capabilities").put("tool_receipt_cause", keeps) })
            try {
                call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
                call.start()
                call.model("f-1", "notes_search", json("query" to "groceries"))
                call.until { call.told("f-1").isNotEmpty() }
                call.hermes.down = true
                fun lost() = call.events.lastOrNull { it.optString("type") == "tool_receipt" && it.optString("status") in setOf("hermes_unreachable", "lost_result") }
                call.until(10_000) { lost() != null }
                val receipt = lost()!!
                assertTrue("the engine's receipt names what lost the link: $receipt", receipt.optString("cause_class").isNotBlank())
                val timeline = callTimelineReceipt(receipt, call.voice)!!
                if (keeps) assertEquals(timelineCause(receipt.getString("cause_class")), timeline.getString("cause_class"))
                else assertFalse("sent to a plugin that does not keep it: $timeline", timeline.has("cause_class"))
            } finally { call.hermes.down = false;call.close() }
        }
    }

    // REVIEW-E5-2 P2-1 (proofQ, inverted): a read queued behind its frame's write that waits for an approval holds only its own frame:
    // another frame's quick question is answered at once (before: about 6 s later, at the queued read's CALL_HOLD_MS).
    @Test(timeout = 30000) fun aReadQueuedBehindItsFramesWriteHoldsNoOtherFramesAnswer() = runBlocking {
        val call = Call(v3())
        try {
            call.hermes.invoke = { body, _ ->
                if (body.optString("tool") == "t_hermes_notes_create") call.hermes.ok(fixture("invoke_needs_approval").put("request_id", body.getString("request_id")))
                else call.hermes.ok(fixture("invoke_completed").put("request_id", body.getString("request_id")))
            }
            call.start()
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray()
                .put(json("id" to "w-1", "name" to "t_hermes_notes_create", "args" to json("title" to "Synthetic note")))
                .put(json("id" to "r-1", "name" to "notes_search", "args" to json("query" to "notes"))))).toString())
            call.until(8_000) { call.prompts().any { it.getString("id") == "w-1" } }   // the approval is said after its settle
            val asked = System.currentTimeMillis()
            call.model("r-2", "notes_search", json("query" to "weather"))           // another frame: a quick question
            call.until(12_000) { call.responses("r-2").isNotEmpty() }
            val waited = System.currentTimeMillis() - asked
            assertTrue("another frame's ready answer waited $waited ms for a read queued behind an approval", waited < 1_500)
            assertTrue("the read still waits for the write", call.told("r-1").none { !it.optBoolean("will_update", true) })
        } finally { call.close() }
    }

    // REVIEW-E5-2 P3-2: a sentence that starts like one of an earlier turn, cut off by the owner or by the session's end, may have been heard
    // (its audio always plays): its held caption is given back before the interruption, in the turn that was cut (before: dropped).
    @Test(timeout = 20000) fun aHeldCaptionCutOffIsGivenBackBeforeTheInterruption() = runBlocking {
        val line = "I searched your past conversations, but I couldn't find any mentions of invoices."
        val start = "I searched your past conversations"
        val shown = mutableMapOf<String, List<String>>()
        for (cut in listOf("owner", "session")) {
            val call = Call(v3())
            try {
                call.start()
                call.content(json("turnComplete" to true))
                call.content(json("outputTranscription" to json("text" to line)));call.content(json("turnComplete" to true))
                call.content(json("outputTranscription" to json("text" to start)))
                if (cut == "owner") call.content(json("interrupted" to true))
                else { val (socket, listener) = call.sessions.last();listener.onClosing(socket, 1001, "Going away") }
                call.until { call.events.any { it.optString("type") == "interrupted" } }
                delay(200)
                shown[cut] = call.events.filter { it.optString("type") == "interrupted" || it.optString("type") == "transcript" && it.optString("role") == "assistant" && it.optString("text").isNotBlank() }
                    .map { if (it.optString("type") == "interrupted") "|" else it.optString("text") }.take(3)
            } finally { call.close() }
        }
        assertEquals(mapOf("owner" to listOf(line, start, "|"), "session" to listOf(line, start, "|")), shown)
    }

    // Device round 4, F1: after a 3-minute outage the link came back only when the first request answered, an updates poll Hermes held for
    // its full 20 s wait ("Lost Hermes" stayed 22.7 s). While the link is lost, a short no-wait read finds its return: within about 2-3 s of
    // Hermes answering, whatever long wait is in flight.
    @Test(timeout = 30000) fun theLinksReturnIsFoundByAShortReadWhateverLongWaitIsInFlight() = runBlocking {
        val call = Call(v3())
        val hold = java.util.concurrent.CountDownLatch(1)
        try {
            call.hermes.invoke = { body, _ -> call.hermes.ok(fixture("invoke_running").put("request_id", body.getString("request_id"))) }
            call.start()
            call.model("f-1", "notes_search", json("query" to "groceries"))
            call.until { call.told("f-1").isNotEmpty() }
            call.hermes.down = true
            call.until(10_000) { call.events.any { it.optString("type") == "hermes_link" && !it.optBoolean("reachable", true) } }
            call.hermes.updatesHold = hold
            val back = call.events.size
            val since = System.currentTimeMillis()
            call.hermes.down = false
            call.until(8_000) { call.events.drop(back).any { it.optString("type") == "hermes_link" && it.optBoolean("reachable") } }
            val took = System.currentTimeMillis() - since
            assertTrue("the link came back $took ms after Hermes answered", took < 3_500)
        } finally { hold.countDown();call.close() }
    }

    // Device round 4, D2: round 4's cut, on the call's own clock. The whole 724-character answer came within 5.6 s of its first audio, with
    // its ~39.9 s of audio (the greeting played at 18.1 characters a second; Gemini Live gives transcription no ordering against audio); the
    // owner cut in 12.3 s after the audio began. The
    // interruption says how much played of how much came, and the caption keeps that share, cut at a word and marked (before: the whole
    // answer).
    private val tramWords = (1..30).joinToString(" ") { "Tram twenty eight climbs through the old district on its route number $it." }.take(724).trimEnd()
    private fun audioFrame(text: String) = json("modelTurn" to json("parts" to JSONArray().put(json("inlineData" to json("mimeType" to "audio/pcm;rate=24000",
        "data" to java.util.Base64.getEncoder().encodeToString(ByteArray(48 * 998)))))), "outputTranscription" to json("text" to text))
    private fun replyCaption(call: Call) = call.events.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }

    @Test(timeout = 30000) fun aCutReplyKeepsOnlyTheWordsThatPlayed() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val call = Call(v3(), clock = { now.get() })
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val start = now.get()
            val pieces = tramWords.chunked((tramWords.length + 39) / 40)
            for (piece in pieces) { now.addAndGet(140);call.content(audioFrame(piece)) }
            now.set(start + 12_300)
            call.content(json("interrupted" to true))
            call.until { call.events.any { it.optString("type") == "interrupted" } }
            val cut = call.events.first { it.optString("type") == "interrupted" }
            assertEquals("the reply's audio, as it came", pieces.size * 998L, cut.getLong("received_ms"))
            assertEquals("played by the cut", 12_300L - 140, cut.getLong("played_ms"))
            val kept = playedCaption(replyCaption(call), cut.getLong("played_ms"), cut.getLong("received_ms"))
            assertTrue("about 12.3 s of speech (round 4: 210-235 heard), got ${kept.length}: $kept", kept.length in 190..250)
            assertTrue(kept.endsWith("…"));assertTrue("cut at a word", tramWords.startsWith(kept.removeSuffix("…")) && tramWords[kept.length - 1] == ' ')
        } finally { call.close() }
    }

    // Device round 4, D2, the same path on Grok: its response ends (turnComplete) when generation ends, while its audio still plays. The
    // reply then ends once its audio has played; the owner's cut before that is a cut with what played, not a finished reply.
    @Test(timeout = 30000) fun aReplyWhoseWordsAllCameEndsOnlyWhenItsAudioHasPlayed() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val call = Call(v3(), clock = { now.get() })
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val start = now.get()
            for (piece in tramWords.chunked((tramWords.length + 39) / 40)) { now.addAndGet(140);call.content(audioFrame(piece)) }
            val ended = call.events.count { it.optString("type") == "turn_complete" }
            call.content(json("turnComplete" to true))
            delay(300)
            assertEquals("no end while its audio plays", ended, call.events.count { it.optString("type") == "turn_complete" })
            now.set(start + 12_300)
            val (socket, listener) = call.sessions.last()
            listener.onMessage(socket, json("serverContent" to json("interrupted" to true)).toString())
            call.until { call.events.any { it.optString("type") == "interrupted" } }
            assertEquals(12_300L - 140, call.events.first { it.optString("type") == "interrupted" }.getLong("played_ms"))
            delay(300)
            assertEquals("the cut, not an end", ended, call.events.count { it.optString("type") == "turn_complete" })
        } finally { call.close() }
    }

    // Device round 4, D2 and RepeatGuard: a cut turn's sentences count as said only as far as its audio played. The next turn saying the
    // sentence that never played shows it; the one that played is still held as a repeat (before: both counted as said).
    @Test(timeout = 30000) fun aSentenceThatNeverPlayedMayBeSaidAgain() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val call = Call(v3(), clock = { now.get() })
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val played = "The first sentence reached the speaker in full. "
            val unplayed = "This second sentence never reached the speaker at all."
            val start = now.get()
            now.addAndGet(100);call.content(audioFrame(played))
            now.addAndGet(100);call.content(audioFrame(unplayed))
            now.set(start + 1_300)
            call.content(json("interrupted" to true))
            call.until { call.events.any { it.optString("type") == "interrupted" } }
            call.content(json("turnComplete" to true))
            val before = call.events.size
            call.content(json("outputTranscription" to json("text" to unplayed)))
            call.content(json("outputTranscription" to json("text" to " " + played.trim())))
            call.content(json("turnComplete" to true))
            delay(300)
            val shown = call.events.drop(before).filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }.joinToString("") { it.optString("text") }
            assertTrue("the sentence that never played is shown: $shown", unplayed in shown)
            assertFalse("the one that played is held as a repeat: $shown", played.trim() in shown)
        } finally { call.close() }
    }

    // Device round 4, D2, in round 4's real order: the owner's speech starts (voice activity), then the provider's interruption, then its
    // turn's end. The figures come on the first cut; the second counts only audio since it (none), so it never trims again; the reply ends once.
    @Test(timeout = 30000) fun aCutInRound4sOrderCarriesItsFiguresOnce() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val call = Call(v3(), clock = { now.get() })
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val start = now.get()
            for (piece in tramWords.chunked((tramWords.length + 39) / 40)) { now.addAndGet(140);call.content(audioFrame(piece)) }
            now.set(start + 12_300)
            val ended = call.events.count { it.optString("type") == "turn_complete" }
            call.server(json("voiceActivity" to json("type" to "ACTIVITY_START")))
            call.content(json("interrupted" to true))
            call.content(json("turnComplete" to true))
            call.until { call.events.count { it.optString("type") == "interrupted" } >= 2 }
            delay(300)
            val cuts = call.events.filter { it.optString("type") == "interrupted" }
            assertEquals(12_300L - 140, cuts[0].getLong("played_ms"));assertTrue(cuts[0].getLong("received_ms") > 30_000)
            assertEquals("the second cut counts only audio since the first", 0L, cuts[1].getLong("received_ms"))
            assertEquals("the reply ends once", ended + 1, call.events.count { it.optString("type") == "turn_complete" })
        } finally { call.close() }
    }

    // REVIEW-E7 P2-1, Root's option (a): the owner types while the model's reply still plays (its generation ended; its turn's end waits for
    // its audio). The reply ends first: its caption turn closes whole, then the typed line starts the next turn with the next reply, in order.
    @Test(timeout = 30000) fun aLineTypedWhileTheReplyPlaysStartsTheNextTurn() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val from = call.events.size
            call.content(audioFrame("The first answer has three parts."))
            call.content(audioFrame(" Part one is short."))
            call.content(audioFrame(" Part two is longer."))
            call.content(json("turnComplete" to true))                         // about 3 s of its audio still to play
            delay(300)
            assertTrue("typed", call.voice.sendText("And the second question?"))
            call.content(audioFrame("The second answer."))
            delay(200)
            val written = captionTurns(call.events.drop(from)).map { turn ->
                mutableListOf<JSONObject>().also { out -> finishDirectTranscriptTurn("call", "chat", 7L, turn, 0L) { out += it } }.map { it.getString("role") to it.getString("text") }
            }
            assertEquals(listOf(listOf("assistant" to "The first answer has three parts. Part one is short. Part two is longer."),
                listOf("user" to "And the second question?", "assistant" to "The second answer.")), written)
        } finally { call.close() }
    }
    /** The service's caption turns: each "transcript" accumulated per role until its turn's end ("turn_complete"); the open turn last. */
    private fun captionTurns(events: List<JSONObject>): List<MutableMap<String, String>> {
        val turns = mutableListOf(mutableMapOf<String, String>())
        for (e in events) when (e.optString("type")) {
            "transcript" -> turns.last()[e.getString("role")] = accumulatedCaption(turns.last()[e.getString("role")].orEmpty(), e.getString("text"))
            "turn_complete" -> if (turns.last().isNotEmpty()) turns += mutableMapOf<String, String>()
        }
        return turns.filter { it.isNotEmpty() }
    }

    /**
     * BeaconCallService's caption path for a direct call, step for step with its own functions: a "transcript" is accumulated per role
     * (accumulatedCaption), shown (withTranscript) and synced live (directCaption); "interrupted" marks the live reply interrupted on screen
     * (handleControl), cuts the captions (cutCaptions) and ends the turn (interruptsReply, finishDirectTranscriptTurn: shown and synced, then
     * the cut's interruption); "turn_complete" ends it. The sync is the call's NativeCallTranscriptSync, against a recorder of what Hermes gets.
     */
    private class ServiceCaptions(scope: CoroutineScope) {
        val uploads = CopyOnWriteArrayList<JSONObject>()
        @Volatile var finalized: JSONObject? = null
        val sync = NativeCallTranscriptSync({ path, _, body ->
            if (path.endsWith("/events")) body!!.getJSONArray("events").let { a -> for (i in 0 until a.length()) uploads += a.getJSONObject(i) }
            else if (path.endsWith("/finalize")) finalized = JSONObject(body.toString())
            JSONObject().put("stored", true).put("closed", true).put("transcript_status", "saved")
        }, "call", scope, {})
        var state = CallState(active = true, callId = "call")
        private val captions = mutableMapOf<String, String>()
        private var turn = 0L
        fun event(value: JSONObject) = when (value.optString("type")) {
            "transcript" -> {
                val role = value.getString("role");val text = accumulatedCaption(captions[role].orEmpty(), value.getString("text"));captions[role] = text
                val caption = directCaption(JSONObject(value.toString()), "call", "chat", turn, text)
                sync.event(JSONObject(caption.toString()).put("at_ms", 0));show(caption)
            }
            "interrupted" -> {
                state = state.copy(transcript = "", transcripts = state.transcripts.map { if (!it.final && it.role == "assistant") it.copy(interrupted = true) else it })
                cutCaptions(captions, value);finish(true)
            }
            "turn_complete" -> finish(false)
            else -> Unit
        }
        private fun show(value: JSONObject) { state = state.withTranscript(value, "chat", "call") }
        private fun finish(interrupted: Boolean) {
            val cut = interruptsReply(interrupted, captions);val at = turn
            turn = finishDirectTranscriptTurn("call", "chat", turn, captions, 0L, cut) { show(it);sync.event(it) }
            if (cut) sync.event(json("event_id" to "call:assistant:interrupt:$at", "turn_id" to at.toString(), "type" to "interrupted", "interrupted" to true, "at_ms" to 0))
        }
        /** The plugin's finalize rule (mobile.py finalize, 1.2.0): each line group with no final makes the call "partial". */
        fun unfinalized() = uploads.filter { it.optString("type") == "transcript" }
            .groupBy { it.optString("display_event_id").ifBlank { "call:" + it.optString("role") + ":" + it.optString("turn_id") } }.count { (_, g) -> g.none { it.optBoolean("final") } }
    }
    private suspend fun captionsAtHermes(events: List<JSONObject>): ServiceCaptions {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = ServiceCaptions(worker)
        try { for (e in events) service.event(e);assertTrue(withTimeout(20000) { service.sync.finish() }) } finally { worker.cancel() }
        return service
    }

    // REVIEW-E7 P2-2 (the review's proofP): a cut before anything of the reply played, its words ahead of its audio. Nothing played, no
    // assistant line: none on screen, none in what Hermes gets, and no line left without a final, so the call is not saved "partial".
    @Test(timeout = 30000) fun aCutBeforeAnythingPlayedLeavesNoAssistantLine() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val from = call.events.size
            call.content(json("outputTranscription" to json("text" to "Here is the long answer you never heard.")))   // words first, no audio yet
            call.content(json("interrupted" to true))
            call.until { call.events.any { it.optString("type") == "interrupted" } }
            val events = call.events.drop(from)
            assertTrue("its words were shown live", events.any { it.optString("type") == "transcript" && it.optString("role") == "assistant" })
            val service = captionsAtHermes(events)
            assertEquals("no assistant line on screen", emptyList<CallTranscript>(), service.state.transcripts.filter { it.role == "assistant" })
            assertEquals("none at Hermes", emptyList<JSONObject>(), service.uploads.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" })
            assertEquals("no line without a final", 0, service.unfinalized())
            assertFalse("not saved partial", service.finalized!!.getBoolean("incomplete"))
        } finally { call.close() }
    }

    // REVIEW-E7 P2-2 (the review's proofP2): round 4's two-event order with the provider still sending between them. The owner's speech cuts
    // the playing reply (its share kept, a final); the reply's next words arrive; the provider's interruption has no audio since the first cut,
    // so those words are withdrawn: on screen and at Hermes only the played share, every line final, the call not "partial".
    @Test(timeout = 30000) fun wordsBetweenTheOwnersCutAndTheProvidersAreWithdrawn() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            call.content(json("turnComplete" to true))
            val from = call.events.size
            call.content(audioFrame("Here is the start of the answer."))              // about 1 s of audio, playing
            delay(200)
            call.server(json("voiceActivity" to json("type" to "ACTIVITY_START")))   // the owner speaks: the first cut
            call.until { call.events.any { it.optString("type") == "interrupted" } }
            call.content(json("outputTranscription" to json("text" to " And here is more you never heard.")))
            call.content(json("interrupted" to true))
            call.until { call.events.count { it.optString("type") == "interrupted" } >= 2 }
            val service = captionsAtHermes(call.events.drop(from))
            val shown = service.state.transcripts.filter { it.role == "assistant" }
            assertEquals("one assistant line on screen: $shown", 1, shown.size)
            assertTrue("the played share, final and cut: $shown", shown[0].final && shown[0].interrupted && shown[0].text.endsWith("…") &&
                "Here is the start of the answer.".startsWith(shown[0].text.removeSuffix("…")))
            val stored = service.uploads.filter { it.optString("type") == "transcript" && it.optString("role") == "assistant" }
            assertEquals("Hermes gets that line only", listOf(shown[0].text to true), stored.map { it.getString("text") to it.optBoolean("final") })
            assertEquals("no line without a final", 0, service.unfinalized())
            assertFalse("not saved partial", service.finalized!!.getBoolean("incomplete"))
        } finally { call.close() }
    }

    // REVIEW-E7 P3-1 (the review's proofF): only the updates poll fails, at once, while Hermes answers everything else. While the link is lost
    // only the probe tries; each return wakes the poll once, which loses the link again at once; the probe's backoff goes on across those
    // quick losses (LINK_FLAP_MS), so the returns, and the poll with them, come at the backoff's pace (0.5, 1, 2, 2 s): both within the 5
    // tries such a backoff makes in 6 s, and the poll goes at most once per probe (E7: 11 probes, 13 polls).
    @Test(timeout = 30000) fun aPollThatKeepsFailingKeepsItsBackoff() = runBlocking {
        val call = Call(v3())
        try {
            call.start()
            delay(500)
            val before = call.hermes.paths().size
            call.hermes.updatesLost = 100_000
            delay(6_000)
            val paths = call.hermes.paths().drop(before)
            val probes = paths.count { it.endsWith("/mobile/calls/call") };val polls = paths.count { it.endsWith("/updates") }
            println("P3-1 probes=$probes polls=$polls other=${paths.size - probes - polls}")
            assertTrue("polls within the backoff: $polls", polls in 1..5)
            assertTrue("probes within the backoff: $probes", probes <= 5)
            assertTrue("the poll goes once per return: $polls polls, $probes probes", polls <= probes + 1)
        } finally { call.hermes.updatesLost = 0;call.close() }
    }
}
