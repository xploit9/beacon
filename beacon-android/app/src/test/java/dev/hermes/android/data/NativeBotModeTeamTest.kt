package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.json
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bot Mode as a team: New chat (two to six bots, an optional name), the list's members/time/newest line, and rename/delete of any listed space. */
class NativeBotModeTeamTest {
    @Test fun aNewChatNeedsTwoToSixBotsAndAlwaysHoldsTheOneInUse() {
        assertNull("only the bot in use", botModeChosen(emptySet(), "default"))
        assertEquals(listOf("coder", "default"), botModeChosen(setOf("coder"), "default"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), botModeChosen(setOf("b", "c", "d", "e", "f"), "a"))
        assertNull("seven bots", botModeChosen(setOf("b", "c", "d", "e", "f", "g"), "a"))
        assertEquals("a blank bot in use is no bot", listOf("a", "b"), botModeChosen(setOf("a", "b"), ""))
        assertEquals(listOf("a", "b"), botModeChosen(setOf("a", "b"), "a"))
    }

    @Test fun spacesCarryTheirBotsTheirLastChangeAndTheNewestLineReadHere() {
        val rows = listOf(
            json("room_id" to "r1", "name" to "One", "members" to JSONArray().put(json("profile" to "default")).put(json("profile" to "coder")).put(json("profile" to "")), "updated_at" to 1_800_000_000.75),
            json("room_id" to "r2", "name" to "Two"),
            json("room_id" to "r3", "name" to "Three", "updated_at" to -5, "members" to JSONArray()),
        )
        val spaces = botModeSpaces(rows, mapOf("r1" to BotModePreview("You", "Hi")))
        assertEquals(listOf("default", "coder"), spaces[0].members)
        assertEquals(1_800_000_000L, spaces[0].updatedAt)
        assertEquals(BotModePreview("You", "Hi"), spaces[0].last)
        assertEquals(BotModeSpace("r2", "Two"), spaces[1])
        assertEquals(0L, spaces[2].updatedAt)
    }

    @Test fun thePreviewIsTheNewestMessageOnOneLine() {
        val events = listOf(
            BotModeEvent("1", "You", "Plan"), BotModeEvent("2", "coder", "  Plan   the\nreview\t now "), BotModeEvent("3", "coder", "Provider balance is exhausted", failed = true),
        )
        assertEquals(BotModePreview("coder", "Plan the review now"), botModePreview(events))
        assertEquals(140, botModePreview(listOf(BotModeEvent("1", "You", "x".repeat(400))))!!.text.length)
        assertNull(botModePreview(emptyList()))
        assertNull(botModePreview(listOf(BotModeEvent("1", "coder", "failed", failed = true))))
    }

    private val requests = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, JSONObject?>>()
    private val rooms = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
    @Volatile private var renameFails = false
    @Volatile private var createdName = NEW_SPACE_NAME

    private fun members() = JSONArray().put(json("profile" to "default", "member_id" to "default", "handle" to "hermes")).put(json("profile" to "coder", "member_id" to "coder", "handle" to "coder"))

    private fun reply(method: String, path: String, body: JSONObject?): Pair<Int, JSONObject> {
        val room = path.substringAfter("/space/", "").substringBefore('/')
        return when {
            path.endsWith("/bot-mode") -> 200 to json(
                "bots" to JSONArray(listOf("default", "coder", "writer").map { json("profile" to it, "title" to it, "description" to "") }),
                "spaces" to JSONArray(rooms.map { (id, name) -> json("room_id" to id, "name" to name, "members" to members(), "updated_at" to 1_800_000_000.5) }),
                "capabilities" to json("rename" to true, "delete" to true))
            method == "POST" && path.endsWith("/space") -> { rooms["new-room"] = createdName; 200 to json("room" to json("room_id" to "new-room", "name" to createdName)) }
            method == "POST" && path.endsWith("/rename") ->
                if (renameFails) 503 to json("detail" to "busy") else { rooms[room] = body!!.getString("name"); 200 to json("room" to json("room_id" to room, "name" to rooms[room])) }
            method == "POST" && path.endsWith("/delete") -> {
                rooms.remove(room)
                200 to json("tombstone" to json("room_id" to room, "disbanded_at" to 1.5, "idempotent" to false, "event" to json("room_id" to room, "kind" to "room.disbanded", "event_id" to "system:room-disbanded")))
            }
            path.contains("/space/") -> 200 to json(
                "room" to json("room_id" to room, "name" to (rooms[room] ?: ""), "members" to members()),
                "events" to JSONArray().put(json("event_id" to "e1", "kind" to "message.member", "created_at" to 1_800_000_100, "payload" to json("member_id" to "coder", "text" to "Plan   the\nreview"))),
                "capabilities" to json("rename" to true, "delete" to true))
            else -> 404 to json("detail" to "not found")
        }
    }

    private fun client() = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { val buffer = okio.Buffer(); it.writeTo(buffer); JSONObject(buffer.readUtf8()) }
            requests += Triple(request.method, request.url.encodedPath, body)
            val (code, result) = reply(request.method, request.url.encodedPath, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }

    /** A client whose Hermes never answers (503), for reads that must come from the cache alone. */
    private fun silent(owner: String, profile: String, asked: CompletableDeferred<Unit>? = null) = BeaconClient(AuthContext("https://native.example", emptyList(), owner, profile), null) { builder ->
        builder.addInterceptor { chain -> asked?.complete(Unit); Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Fixture").body("{}".toResponseBody("application/json".toMediaType())).build() }
    }

    private suspend fun awaitIdle(controller: NativeBotModeController) {
        yield()
        withTimeout(5000) { while (controller.state.value.loading) delay(5) }
    }

    @Test fun aNewChatIsNamedThroughTheRenameRouteAndOpens() = runBlocking<Unit> {
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.startNewChat("coder")
            assertEquals(setOf("coder"), controller.state.value.selectedBots)
            controller.createSpace("  Review team "); awaitIdle(controller)
            val create = requests.first { it.first == "POST" && it.second.endsWith("/space") }.third!!
            val chosen = create.getJSONArray("profiles")
            assertEquals(listOf("coder", "default"), (0 until chosen.length()).map(chosen::getString))
            val rename = requests.first { it.second.endsWith("/space/new-room/rename") }.third!!
            assertEquals("Review team", rename.getString("name"))
            assertEquals(NEW_SPACE_NAME, rename.getString("expected_name"))
            assertEquals("new-room", controller.state.value.selectedSpace)
            assertEquals("Review team", controller.state.value.spaces.first().name)
            assertEquals(listOf("coder", "default"), controller.state.value.spaces.first().members)
            assertEquals("", controller.state.value.error)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun noNameOrAnAlreadyNamedSpaceMeansNoRename() = runBlocking<Unit> {
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.startNewChat("coder")
            controller.createSpace(""); awaitIdle(controller)
            assertTrue(requests.none { it.second.endsWith("/rename") })
            assertEquals(NEW_SPACE_NAME, controller.state.value.spaces.first().name)

            // A retry that finds a space Hermes already named keeps that name.
            controller.home(); rooms.clear(); createdName = "Older name"
            controller.createSpace("Another name"); awaitIdle(controller)
            assertTrue(requests.none { it.second.endsWith("/rename") })
            assertEquals("Older name", controller.state.value.spaces.first().name)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun aNameThatCouldNotBeSavedStillOpensTheNewChatAndSaysSo() = runBlocking<Unit> {
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            renameFails = true
            controller.startNewChat("coder")
            controller.createSpace("Review team"); awaitIdle(controller)
            assertEquals("new-room", controller.state.value.selectedSpace)
            assertEquals("The chat was created, but its name wasn't saved.", controller.state.value.error)
            assertEquals(NEW_SPACE_NAME, controller.state.value.spaces.first().name)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun fewerThanTwoBotsNeverReachHermes() = runBlocking<Unit> {
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.startNewChat()
            controller.createSpace("Alone"); awaitIdle(controller)
            assertEquals("Choose two to six bots.", controller.state.value.error)
            assertTrue(requests.none { it.first == "POST" })
            // Five picked plus the one in use is the ceiling.
            for (name in listOf("a", "b", "c", "d", "e", "f", "g")) controller.toggleBot(name)
            assertEquals(5, controller.state.value.selectedBots.size)
            // Opening New chat starts clean, with the bot a long press named already picked, or none; the bot in use is never "picked".
            controller.startNewChat("writer")
            assertEquals(setOf("writer"), controller.state.value.selectedBots)
            controller.startNewChat("default")
            assertEquals(emptySet<String>(), controller.state.value.selectedBots)
            controller.cancelNewChat()
            assertEquals(emptySet<String>(), controller.state.value.selectedBots)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun renameAndDeleteWorkOnAnyListedSpaceWithoutLeavingTheOpenOne() = runBlocking<Unit> {
        rooms["r1"] = "One"; rooms["r2"] = "Two"
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.selectSpace("r1"); awaitIdle(controller)
            controller.renameSpace("Renamed", "r2"); awaitIdle(controller)
            assertEquals("Renamed", controller.state.value.spaces.first { it.id == "r2" }.name)
            assertEquals("r1", controller.state.value.selectedSpace)
            val rename = requests.first { it.second.endsWith("/space/r2/rename") }.third!!
            assertEquals("Two", rename.getString("expected_name"))

            controller.deleteSpace("r2"); awaitIdle(controller)
            assertTrue(requests.any { it.second.endsWith("/space/r2/delete") })
            assertEquals(listOf("r1"), controller.state.value.spaces.map { it.id })
            assertEquals("r1", controller.state.value.selectedSpace)
            assertEquals("", controller.state.value.error)

            // The open space is still the default target.
            controller.deleteSpace(); awaitIdle(controller)
            assertEquals(emptyList<BotModeSpace>(), controller.state.value.spaces)
            assertEquals("", controller.state.value.selectedSpace)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun aSpaceReadHereKeepsItsNewestLineAndTimeInTheListAfterRefreshes() = runBlocking<Unit> {
        rooms["r1"] = "One"
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            assertNull(controller.state.value.spaces.single().last)
            assertEquals(1_800_000_000L, controller.state.value.spaces.single().updatedAt)
            controller.selectSpace("r1"); awaitIdle(controller)
            assertEquals(BotModePreview("coder", "Plan the review"), controller.state.value.spaces.single().last)
            assertEquals(1_800_000_100L, controller.state.value.spaces.single().updatedAt)
            controller.home(); controller.refresh(); awaitIdle(controller)
            assertEquals(BotModePreview("coder", "Plan the review"), controller.state.value.spaces.single().last)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun botModeOpensWithTheLastListAndTheLinesReadOnThisPhone() = runBlocking<Unit> {
        rooms["r1"] = "Team"
        val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
        val cache = ScreenCache({ stored[it.key] }, { scope, value -> stored[scope.key] = value }, { stored.remove(it.key) }, this, quietMs = 0)
        val c = client()
        val first = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, screens = cache) { c }
        try {
            first.refresh(); awaitIdle(first); first.selectSpace("r1"); awaitIdle(first)
            // The roster is written off the main thread; wait until the line read in r1 is in it.
            withTimeout(5000) { while (cache.exact(c.scope, "bot-mode:roster")?.value?.optJSONObject("previews")?.has("r1") != true) delay(5) }
        } finally { first.clear(); c.closeNative() }
        // The next start, before Hermes answers (here it never does): the list and r1's newest line are already there.
        val offline = silent("owner", "default")
        val second = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, screens = cache) { offline }
        try {
            second.refresh(); awaitIdle(second)
            assertEquals(listOf("default", "coder", "writer"), second.state.value.bots.map { it.profile })
            assertEquals(BotModePreview("coder", "Plan the review"), second.state.value.spaces.single().last)
        } finally { second.clear(); offline.closeNative() }

        // Another profile of the same account never sees these bots or lines.
        val other = silent("owner", "coder")
        val third = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, screens = cache) { other }
        try {
            third.refresh(); awaitIdle(third)
            assertTrue(third.state.value.bots.isEmpty() && third.state.value.spaces.isEmpty())
        } finally { third.clear(); other.closeNative() }

        // A cache read that finishes after the account changed shows nothing: a slow phone never shows the last account's bots.
        withTimeout(5000) { while (stored.isEmpty()) delay(5) }
        val gate = CompletableDeferred<Unit>()
        val slow = ScreenCache({ gate.await(); stored[it.key] }, { scope, value -> stored[scope.key] = value }, { stored.remove(it.key) }, this, quietMs = 0)
        val asked = CompletableDeferred<Unit>()
        val mine = silent("owner", "default", asked)
        val someoneElse = BeaconClient(AuthContext("https://native.example", emptyList(), "other-owner", "default"), null)
        var live: BeaconClient = mine
        val fourth = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, screens = slow) { live }
        try {
            fourth.refresh(); yield(); delay(20)
            live = someoneElse
            // Hermes is asked only after the cache read returns, so once it is asked the late read has been handled.
            gate.complete(Unit); withTimeout(5000) { asked.await() }; delay(20)
            assertTrue(fourth.state.value.bots.isEmpty() && fourth.state.value.spaces.isEmpty())
        } finally { fourth.clear(); mine.closeNative(); someoneElse.closeNative() }
    }

    @Test fun aRosterRowRemovedElsewhereIsSavedAgainWithTheSameList() = runBlocking<Unit> {
        val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
        val cache = ScreenCache({ stored[it.key] }, { scope, value -> stored[scope.key] = value }, { stored.remove(it.key) }, this, quietMs = 0)
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, screens = cache) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            withTimeout(5000) { while (cache.exact(c.scope, "bot-mode:roster") == null) delay(5) }
            cache.remove(c.scope, "bot-mode:roster")
            controller.refresh(); awaitIdle(controller)
            withTimeout(5000) { while (cache.exact(c.scope, "bot-mode:roster") == null) delay(5) }
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun rosterChangedReadsTheListAgainRightAway() = runBlocking<Unit> {
        val c = client()
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }, { 1_000L }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            val before = requests.count { it.second.endsWith("/bot-mode") }
            // Within the 30-second window a silent refresh reads nothing.
            controller.refresh(silent = true); awaitIdle(controller)
            assertEquals(before, requests.count { it.second.endsWith("/bot-mode") })
            controller.rosterChanged(); awaitIdle(controller)
            assertEquals(before + 1, requests.count { it.second.endsWith("/bot-mode") })
        } finally { controller.clear(); c.closeNative() }
    }
}
