package dev.hermes.android.voice

import android.content.ContextWrapper
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.phone.PhoneOrigin
import dev.hermes.android.phone.PhoneTools
import dev.hermes.android.phone.Rig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strix vuln-0013 (the brief's 0012, medium), in the real tool dispatch, the phone bridge and the call brief: a conversation started on a
 * locked phone may talk, but until the phone has been unlocked since it started its brief holds nothing private and every tool, Hermes'
 * and the phone's, answers "Unlock your phone first." without reading anything. After an unlock both work, and a conversation that
 * started unlocked keeps its tools when the screen locks again.
 */
class LockedStartCallTest {
    private val context = ContextWrapper(null)
    @After fun reset() { PhoneTools.useEngine(null) }

    // ---- the Hermes tools in a direct call ----

    private fun pluginContext() = json("backend" to "hermes", "profile" to "default", "selected_session_id" to "stored",
        "context_summary" to "Native memory (reference data):\nMEMORY-SECRET", "system_instruction" to "Profile personality: PERSONA-SECRET",
        "owner_first_name" to "NAME-SECRET", "tool_catalogue" to "CATALOGUE-SECRET")

    private fun credentials() = VoiceSessionCredentials("gemini", "fixture-provider-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced")

    private fun client(paths: MutableList<String>, tickets: AtomicInteger): BeaconClient =
        BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                paths += path
                if (path.endsWith("/ws-ticket")) tickets.incrementAndGet()
                val body = when {
                    path.endsWith("/context") -> pluginContext()
                    path.endsWith("/tools") -> json("tools" to JSONArray().put(json("name" to "get_weather", "tool_id" to "a".repeat(64), "description" to "Weather")))
                    else -> json("jobs" to JSONArray())
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    private fun providerFactory(onFrame: (JSONObject) -> Unit, onSetup: (WebSocket, WebSocketListener) -> Unit) = WebSocket.Factory { request, listener ->
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
            override fun send(text: String): Boolean {
                val frame = JSONObject(text)
                onFrame(frame)
                if (frame.has("setup")) { onSetup(this, listener); listener.onMessage(this, JSONObject().put("setupComplete", JSONObject()).toString()) }
                return true
            }
        }
        listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
        socket
    }

    private class Call(val voice: DirectVoice, val scope: CoroutineScope, val client: BeaconClient, val socket: WebSocket, val listener: WebSocketListener,
        val frames: CopyOnWriteArrayList<JSONObject>, val paths: CopyOnWriteArrayList<String>, val tickets: AtomicInteger) {
        fun call(id: String, name: String, args: JSONObject = json()) =
            listener.onMessage(socket, json("toolCall" to json("functionCalls" to JSONArray().put(json("id" to id, "name" to name, "args" to args)))).toString())
        fun responses() = frames.filter { it.has("toolResponse") }.map { it.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0) }
        fun response(id: String) = responses().first { it.getString("id") == id }.getJSONObject("response")
        fun close() { voice.close(); scope.cancel(); client.closeNative() }
    }

    private fun openCall(trust: CallTrust): Call = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Pair<WebSocket, WebSocketListener>>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val frames = CopyOnWriteArrayList<JSONObject>()
        val paths = CopyOnWriteArrayList<String>()
        val tickets = AtomicInteger()
        val client = client(paths, tickets)
        val voice = DirectVoice(client, "call", "stored", scope, {}, { events += it }, {}, credentials = credentials(),
            providerSocketFactory = providerFactory({ frames += it }, { socket, listener -> started.complete(socket to listener) }), trust = trust)
        voice.start()
        val (socket, listener) = withTimeout(3000) { started.await() }
        withTimeout(3000) { while (events.none { it.optString("state") == "listening" }) delay(5) }
        Call(voice, scope, client, socket, listener, frames, paths, tickets)
    }

    private fun instruction(frames: List<JSONObject>) =
        frames.first { it.has("setup") }.getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    @Test(timeout = 15000) fun aLockedStartRefusesEveryHermesToolAndReadsNothing() {
        val call = openCall(CallTrust { true })
        try {
            val read = call.paths.size
            val tools = listOf("hermes_memory" to json("query" to "what do you know about me"), "hermes_context" to json(), "hermes_search" to json("query" to "news"),
                "hermes_skills" to json(), "discover_tools" to json("query" to "mail"),
                "invoke_tool" to json("tool_id" to "a".repeat(64), "arguments" to "{}"), "tool_status" to json("request_id" to "r1"),
                "hermes_work" to json("text" to "do it"), "hermes_callback" to json(), "hermes_cancel" to json("job_id" to "j1"), "get_visual_state" to json())
            for ((name, args) in tools) call.call("id-$name", name, args)
            runBlocking { withTimeout(5000) { while (call.responses().size < tools.size) delay(5) } }
            for ((name, _) in tools) {
                val answer = call.response("id-$name")
                assertEquals(name, "unavailable", answer.getString("status"))
                assertEquals(name, "locked", answer.getString("error_code"))
                assertTrue(name, answer.getString("message").startsWith("Unlock your phone first."))
            }
            assertEquals("nothing was read from Hermes and no work was opened", emptyList<String>(), call.paths.drop(read).filter { !it.endsWith("/context") })
            assertEquals(0, call.tickets.get())
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun theBriefOfALockedStartHasNoMemoryHistoryOrProfileButTheCallTalks() {
        val locked = openCall(CallTrust { true })
        val open = openCall(CallTrust.open())
        try {
            val text = instruction(locked.frames)
            for (secret in listOf("MEMORY-SECRET", "PERSONA-SECRET", "NAME-SECRET", "CATALOGUE-SECRET")) assertFalse("$secret in the locked brief", text.contains(secret))
            assertTrue(text.contains("You are "))
            // Control: the same call started unlocked is told them.
            val whole = instruction(open.frames)
            for (secret in listOf("MEMORY-SECRET", "PERSONA-SECRET", "NAME-SECRET", "CATALOGUE-SECRET")) assertTrue("$secret must be in the whole brief", whole.contains(secret))
        } finally { locked.close(); open.close() }
    }

    @Test(timeout = 15000) fun afterAnUnlockSignalTheHermesToolsWork() {
        val trust = CallTrust { true }
        val call = openCall(trust)
        try {
            call.call("before", "discover_tools", json("query" to "weather"))
            runBlocking { withTimeout(5000) { while (call.responses().none { it.getString("id") == "before" }) delay(5) } }
            assertEquals("locked", call.response("before").getString("error_code"))
            assertTrue(call.paths.none { it.endsWith("/tools") })
            trust.unlocked()
            call.call("after", "discover_tools", json("query" to "weather"))
            runBlocking { withTimeout(5000) { while (call.responses().none { it.getString("id") == "after" }) delay(5) } }
            assertEquals("get_weather", call.response("after").getJSONArray("tools").getJSONObject(0).getString("name"))
            assertTrue(call.paths.any { it.endsWith("/tools") })
        } finally { call.close() }
    }

    @Test(timeout = 15000) fun aCallStartedUnlockedKeepsItsHermesToolsWhenTheScreenLocksLater() {
        var locked = false
        val call = openCall(CallTrust { locked })
        try {
            locked = true
            call.call("pocket", "discover_tools", json("query" to "weather"))
            runBlocking { withTimeout(5000) { while (call.responses().none { it.getString("id") == "pocket" }) delay(5) } }
            assertEquals("get_weather", call.response("pocket").getJSONArray("tools").getJSONObject(0).getString("name"))
        } finally { call.close() }
    }

    // ---- the phone tools in a call ----

    private fun rig() = Rig().also { it.settings.everything(); PhoneTools.useEngine(it.engine) }
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun bridge(trust: CallTrust) = PhoneVoiceBridge(context, PhoneOrigin.LiveCall, 5_000, trust = trust) { it.text() }

    @Test fun aLockedStartRefusesEveryPhoneToolWithNoCardAndTheActivityListSaysLocked() = runBlocking<Unit> {
        val rig = rig()
        val events = CopyOnWriteArrayList<JSONObject>()
        val bridge = bridge(CallTrust { true })
        val read = bridge.call("l1", "phone_calendars", JSONObject(), events::add)
        assertEquals("unavailable", read.getString("error"))
        assertEquals("Unlock your phone first.", read.getString("message"))
        val write = bridge.call("l2", "phone_timer_set", obj("seconds" to 60), events::add)
        assertEquals("Unlock your phone first.", write.getString("message"))
        assertTrue("no card for a locked start", events.isEmpty())
        assertEquals(listOf("locked", "locked"), rig.log.entries.map { it.outcome })
        assertEquals("no calendar or contact was read", 0, rig.calendar.listQueries)
        assertTrue(rig.actions.timers.isEmpty())
    }

    @Test fun afterAnUnlockSignalThePhoneToolsWorkAgain() = runBlocking<Unit> {
        val rig = rig()
        val trust = CallTrust { true }
        val bridge = bridge(trust)
        val events = CopyOnWriteArrayList<JSONObject>()
        assertEquals("unavailable", bridge.call("u1", "phone_calendars", JSONObject(), events::add).getString("error"))
        trust.unlocked()
        assertTrue(bridge.call("u2", "phone_calendars", JSONObject(), events::add).has("calendars"))
        val job = launch(Dispatchers.Default) { bridge.call("u3", "phone_timer_set", obj("seconds" to 60), events::add) }
        withTimeout(5_000) { while (events.none { it.optString("type") == "tool_approval" }) delay(5) }
        job.cancel(); job.join()
        assertTrue(rig.actions.timers.isEmpty())
    }

    @Test fun aCallStartedUnlockedKeepsReadingTheCalendarWithTheScreenOff() = runBlocking<Unit> {
        val rig = rig()
        val bridge = bridge(CallTrust { false })
        val events = CopyOnWriteArrayList<JSONObject>()
        // The phone locks (a call in a pocket): the owner's call goes on reading the calendar.
        rig.locked = true
        assertTrue(bridge.call("p1", "phone_calendars", JSONObject(), events::add).has("calendars"))
        // A change still shows its card, and the tap that approves it needs the unlocked screen.
        val job = launch(Dispatchers.Default) { bridge.call("p2", "phone_timer_set", obj("seconds" to 60), events::add) }
        withTimeout(5_000) { while (events.none { it.optString("type") == "tool_approval" }) delay(5) }
        job.cancel(); job.join()
        assertTrue(rig.actions.timers.isEmpty())
    }

    // ---- the pieces are wired to one trust per conversation ----

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    @Test fun theServiceGivesTheDispatchAndThePhoneBridgeTheTrustOfTheCallsStart() {
        val service = source("voice/BeaconCallService.kt")
        assertTrue(service.contains("val trust = CallTrust.forPhone(app)"))
        assertTrue(service.contains("phoneOrigin, trust)"))
        assertTrue(service.contains("trust=spec.trust"))
        assertTrue(service.contains("PhoneVoiceBridge(applicationContext,spec.phoneOrigin,trust=spec.trust)"))
        // A locked start never takes the warm session or the context read before the tap: both carry the owner's memory.
        assertTrue(service.contains("val prefetched = if (trust.trusted()) target?.let"))
        assertTrue(service.contains("warm = if (target != null && prefetched != null) prewarm.claim(target, voiceSettings) else null"))
    }

    @Test fun theDispatchRefusesBeforeAnythingRunsAndTheBriefIsWithheldWhereItIsApplied() {
        val direct = source("voice/DirectVoice.kt")
        assertTrue(direct.contains("if(!trust.trusted()&&name in DIRECT_CALL_TOOLS) { toolResponse(id,name,lockedToolResult());return }"))
        assertTrue(direct.contains("liveSetup(credentials,callBrief(context,trust.trusted()),resumption,recentSpeech())"))
        assertTrue(direct.contains("warm?.takeIf { !warmUsed&&!greeted&&!it.dead&&trust.trusted() }"))
        val bridge = source("voice/PhoneVoiceTools.kt")
        assertTrue(bridge.contains("if (!trust.trusted()) {"))
    }

    @Test fun theBriefIsWholeOnceTheConversationIsTrusted() {
        val context = pluginContext()
        assertTrue(callBrief(context, trusted = true).toString().contains("MEMORY-SECRET"))
        assertFalse(callBrief(context, trusted = false).toString().contains("MEMORY-SECRET"))
    }
}
