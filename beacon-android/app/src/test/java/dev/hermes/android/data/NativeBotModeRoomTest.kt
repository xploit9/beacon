package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeBotModeRoomTest {
    @Test fun aRoomIsReadEveryTwoSecondsFasterWhileABotAnswers() {
        assertEquals(3_000L, botModePollMillis(false))
        assertEquals(1_000L, botModePollMillis(true))
    }

    @Test fun typingIsTheMembersHermesSaysAreAnsweringOnceEach() {
        val data = JSONObject("""{"driver_status":{"working":true,"member_activity":[{"member_id":"coder","status":"running"},{"member_id":"coder","status":"queued"},{"member_id":"","status":"running"},{"member_id":"academic","status":"stopping"}]}}""")
        assertEquals(listOf("coder", "academic"), botModeTyping(data))
        // A bot waiting its turn is queued, not typing (as in Hermes' own group room).
        assertEquals(emptyList<String>(), botModeTyping(JSONObject("""{"driver_status":{"working":true,"member_activity":[{"member_id":"academic","status":"queued"}]}}""")))
        assertEquals(emptyList<String>(), botModeTyping(JSONObject("""{"driver_status":{"working":true}}""")))
        assertEquals(emptyList<String>(), botModeTyping(JSONObject("""{}""")))
    }

    @Test fun theRosterKeepsHandlesAndFallsBackToHermesOwnRuleForAMissingOne() {
        val room = JSONObject("""{"members":[{"member_id":"m1","profile":"coder","handle":"coder","display_name":"Code Wizard"},{"profile":"default"},{"profile":"academic"},{"member_id":"x"}]}""")
        assertEquals(listOf(BotModeMember("m1", "coder", "coder", "Code Wizard"), BotModeMember("default", "default", "hermes"), BotModeMember("academic", "academic", "academic")), botModeRoster(room))
        assertEquals(emptyList<BotModeMember>(), botModeRoster(null))
    }

    @Test fun eventsCarryTheirTimeInSeconds() {
        val events = botModeEvents(JSONObject("""{"events":[{"event_id":"1","kind":"message.user","created_at":1800000000.75,"payload":{"text":"Hi"}},{"event_id":"2","kind":"message.member","payload":{"member_id":"coder","text":"Ready"}},{"event_id":"3","kind":"turn.failed","created_at":1800000100,"payload":{"member_id":"coder","reason_code":"cancelled"}}]}"""))
        assertEquals(listOf(1_800_000_000L, 0L, 1_800_000_100L), events.map { it.at })
    }

    @Test fun readingARoomShowsWhoIsTypingAndLeavingClearsIt() = runBlocking {
        val working = java.util.concurrent.atomic.AtomicBoolean(true)
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath.endsWith("/bot-mode")) json("bots" to JSONArray(), "spaces" to JSONArray().put(json("room_id" to "room", "name" to "Room")))
                else json("room" to json("room_id" to "room", "name" to "Room", "members" to JSONArray().put(json("member_id" to "coder", "profile" to "coder", "handle" to "coder")).put(json("profile" to "academic"))),
                    "events" to JSONArray(),
                    "driver_status" to json("running" to true, "working" to working.get(), "member_activity" to JSONArray().apply { if (working.get()) put(json("member_id" to "coder", "status" to "running")) }))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { client }
        try {
            controller.refresh(); awaitIdle(controller); controller.selectSpace("room"); awaitIdle(controller)
            assertEquals(listOf("coder"), controller.state.value.typing)
            assertTrue(controller.state.value.working)
            assertEquals(listOf("coder", "academic"), controller.state.value.roster.map { it.handle })
            working.set(false)
            controller.refresh(); awaitIdle(controller)
            assertEquals(emptyList<String>(), controller.state.value.typing)
            assertFalse(controller.state.value.working)
            working.set(true)
            controller.refresh(); awaitIdle(controller)
            assertTrue(controller.state.value.working)
            controller.home()
            assertTrue(controller.state.value.typing.isEmpty() && controller.state.value.roster.isEmpty() && !controller.state.value.working)
        } finally { controller.clear(); client.closeNative() }
    }

    private suspend fun awaitIdle(controller: NativeBotModeController) {
        yield()
        withTimeout(5000) { while (controller.state.value.loading) delay(5) }
    }
}
