package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeBotModeTest {
    @Test fun renameRetryKeepsItsIdentityUntilARefreshConfirmsAnotherName() = runBlocking {
        val name = java.util.concurrent.atomic.AtomicReference("Original")
        val sent = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.method == "POST") {
                    val buffer=okio.Buffer(); request.body!!.writeTo(buffer); sent+=JSONObject(buffer.readUtf8())
                    json("detail" to "Uncertain rename acknowledgement")
                } else if (request.url.encodedPath.endsWith("/bot-mode")) json("bots" to org.json.JSONArray(), "spaces" to org.json.JSONArray().put(json("room_id" to "room", "name" to name.get())))
                else json("room" to json("room_id" to "room", "name" to name.get()), "events" to org.json.JSONArray())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(request.method=="POST") 503 else 200).message("Fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val controller=NativeBotModeController(this,SavedStateHandle(),{_,_->}) { client }
        try {
            controller.refresh();awaitIdle(controller);controller.selectSpace("room");awaitIdle(controller)
            controller.renameSpace("Target");awaitIdle(controller);controller.renameSpace("Target");awaitIdle(controller)
            assertEquals(sent[0].getString("event_id"),sent[1].getString("event_id"))
            name.set("Changed elsewhere");controller.refresh();awaitIdle(controller)
            controller.renameSpace("Target");awaitIdle(controller)
            assertEquals("Changed elsewhere",sent[2].getString("expected_name"))
            assertNotEquals(sent[1].getString("event_id"),sent[2].getString("event_id"))
        } finally { controller.clear();client.closeNative() }
    }

    @Test fun nativeRoomIdsCannotOverwriteOtherDraftsAndSafePendingEventsStillMigrate() = runBlocking {
        val rooms = mutableListOf("room", "room:event", "room:event-text", "rename:room")
        val sent = mutableListOf<JSONObject>()
        var failSend = true
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val result = if (request.method == "POST") {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer); sent += JSONObject(buffer.readUtf8())
                    if (failSend) json("detail" to "Unknown acknowledgement") else json("accepted" to true)
                } else if (request.url.encodedPath.endsWith("/bot-mode")) json("bots" to org.json.JSONArray(), "spaces" to org.json.JSONArray(rooms.map { json("room_id" to it, "name" to it) }))
                else json("room" to json("room_id" to request.url.pathSegments.last()), "events" to org.json.JSONArray())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (request.method == "POST" && failSend) 503 else 200).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val saved = SavedStateHandle()
        val legacy = "bot-mode:${c.scope.key}:"
        for (id in rooms.drop(1)) saved[legacy + id] = "Retained $id draft"
        val controller = NativeBotModeController(this, saved, { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.selectSpace("room"); awaitIdle(controller)
            controller.draft("Hello"); controller.send(); awaitIdle(controller)
            val event = sent.last().getString("event_id")
            controller.send(); awaitIdle(controller)
            assertEquals(event, sent.last().getString("event_id"))
            controller.renameSpace("Renamed"); awaitIdle(controller)
            for (id in rooms.drop(1)) {
                assertEquals("Retained $id draft", saved.get<String>(legacy + id))
                controller.selectSpace(id); awaitIdle(controller)
                assertEquals("Retained $id draft", controller.state.value.draft)
            }
            // Revoked rooms disappear from the roster but their saved drafts remain.
            rooms.removeAll { it != "room" }
            controller.clear(); controller.refresh(); awaitIdle(controller)
            controller.selectSpace("room"); awaitIdle(controller)
            val freshSaved = SavedStateHandle(mapOf(
                legacy + "room:event" to "Retained removed-room draft",
                legacy + "room:event-text" to "Hello",
                legacy + "rename:room" to "Retained removed rename-room draft",
            ))
            val afterUpgrade = NativeBotModeController(this, freshSaved, { _, _ -> }) { c }
            try {
                afterUpgrade.refresh(); awaitIdle(afterUpgrade)
                afterUpgrade.selectSpace("room"); awaitIdle(afterUpgrade)
                afterUpgrade.draft("Hello"); afterUpgrade.send(); awaitIdle(afterUpgrade)
                assertEquals(sent.last().getString("event_id"), java.util.UUID.fromString(sent.last().getString("event_id")).toString())
                val beforeRename = sent.size
                afterUpgrade.renameSpace("Renamed"); awaitIdle(afterUpgrade)
                assertEquals(beforeRename + 1, sent.size)
                assertEquals("Renamed", sent.last().getString("name"))
                assertEquals("Retained removed rename-room draft", freshSaved.get<String>(legacy + "rename:room"))
            } finally { afterUpgrade.clear() }
            // Ordinary app-created rooms keep their uncertain-send identity on upgrade.
            rooms += "cypher-safe"
            val oldEvent = java.util.UUID.randomUUID().toString()
            saved[legacy + "cypher-safe"] = "Retry retained draft"
            saved[legacy + "cypher-safe:event"] = oldEvent
            saved[legacy + "cypher-safe:event-text"] = "Retry retained draft"
            controller.refresh(); awaitIdle(controller)
            controller.selectSpace("cypher-safe"); awaitIdle(controller)
            controller.send(); awaitIdle(controller)
            assertEquals(oldEvent, sent.last().getString("event_id"))
            assertEquals("Retry retained draft", controller.state.value.draft)
            assertEquals(oldEvent, saved.get<String>(legacy + "cypher-safe:event"))
            failSend = false
            controller.send(); awaitIdle(controller)
            assertEquals(oldEvent, sent.last().getString("event_id"))
            controller.draft("Retry retained draft"); controller.send(); awaitIdle(controller)
            assertNotEquals("A completed legacy event cannot be imported for a new turn", oldEvent, sent.last().getString("event_id"))
        } finally { controller.clear() }
    }

    private suspend fun awaitIdle(controller: NativeBotModeController) {
        yield()
        withTimeout(5000) { while (controller.state.value.loading) delay(5) }
    }
    @Test fun creationRetriesKeepIdentityButNewSpacesGetNewIdentities() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        var failCreate = true
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); var status = 200
                val result = if (request.method == "POST" && request.url.encodedPath.endsWith("/space")) {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer); bodies += JSONObject(buffer.readUtf8())
                    if (failCreate) { status = 503; json("detail" to "Acknowledgement unavailable") }
                    else json("room" to json("room_id" to "new-room", "name" to "Shared space"))
                } else json("bots" to org.json.JSONArray(), "spaces" to org.json.JSONArray(), "events" to org.json.JSONArray())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> fail("Unexpected Bot Chat") }) { c }
        controller.refresh(); awaitIdle(controller)
        controller.toggleBot("default"); controller.toggleBot("writer")
        controller.createSpace(); awaitIdle(controller)
        assertTrue(controller.state.value.error.isNotBlank())
        failCreate = false
        controller.createSpace(); awaitIdle(controller)
        assertEquals(bodies[0].getString("request_id"), bodies[1].getString("request_id"))
        assertEquals("new-room", controller.state.value.selectedSpace)
        controller.home(); controller.createSpace(); awaitIdle(controller)
        assertNotEquals(bodies[1].getString("request_id"), bodies[2].getString("request_id"))
        controller.clear()
    }

    @Test fun silentRefreshNeverBecomesBlockingPageLoading() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var requests = 0
        var now = 1_000L
        var opened = false
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/bot-mode") && ++requests > 1) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body((if (chain.request().url.encodedPath.endsWith("/chat")) "{\"profile\":\"default\",\"title\":\"Bot Chat\",\"stored_session_id\":\"bot-chat\"}" else "{\"bots\":[{\"profile\":\"default\",\"title\":\"Cypher\"}],\"spaces\":[]}").toResponseBody("application/json".toMediaType())).build()
            }
        }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> opened = true }, clock = { now }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            repeat(5) { controller.refresh(silent = true); yield() }
            assertEquals("Fresh roster should be reused", 1, requests)
            now = 32_000L
            controller.refresh(silent = true)
            yield()
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, java.util.concurrent.TimeUnit.SECONDS) })
            assertFalse("A background read must not insert a loading row or disable navigation", controller.state.value.loading)
            assertEquals("Cypher", controller.state.value.bots.single().title)
            controller.openBot(BotModeBot("default", "Cypher", ""))
            withTimeout(2000) { while (!opened) delay(5) }
            assertEquals("bot-chat", controller.state.value.sessionId)
        } finally { release.countDown();controller.clear() }
    }

    @Test fun projectionShowsOnlyNativeConversationEvents() {
        val events = botModeEvents(JSONObject("""{"events":[{"event_id":"1","kind":"room.created","payload":{"text":"internal"}},{"event_id":"2","kind":"message.user","payload":{"text":"Hi"}},{"event_id":"3","kind":"message.member","payload":{"member_id":"writer","text":"Ready"}}]}"""))
        assertEquals(listOf("You", "writer"), events.map { it.speaker })
        assertEquals(listOf("Hi", "Ready"), events.map { it.text })
    }
    @Test fun approvalsKeepExactNativeCoordinatesAndCannotGrantForever() {
        val action = BotModeAction("approval", "task", "writer", "request", 7, "Write file", listOf("once", "deny"))
        val body = action.payload("once")
        assertEquals("request", body.getString("request_id"))
        assertEquals(7, body.getInt("execution_generation"))
        assertEquals("writer", body.getString("member_id"))
        assertTrue(runCatching { action.payload("always") }.isFailure)
        assertEquals(setOf("kind", "task_id"), BotModeAction("retry", "task").payload().keys().asSequence().toSet())
    }
    @Test fun uncertainSendRetainsScopedDraftAndReusesEventIdentity() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        var failSend = true
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                assertEquals("default", request.url.queryParameter("profile"))
                var status = 200
                val result = when {
                    request.url.encodedPath.endsWith("/messages") -> {
                        val buffer = okio.Buffer(); request.body!!.writeTo(buffer); sent += JSONObject(buffer.readUtf8())
                        if (failSend) { status = 503; json("detail" to "Unknown acknowledgement; refresh first") } else json("accepted" to true)
                    }
                    request.url.encodedPath.endsWith("/space/room") -> JSONObject("""{"room":{"room_id":"room"},"events":[],"driver_status":{"running":true}}""")
                    else -> JSONObject("""{"bots":[],"spaces":[{"room_id":"room","name":"Team"}]}""")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Native").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val saved = SavedStateHandle()
        val controller = NativeBotModeController(this, saved, { _, _ -> fail("Unexpected Bot Chat open") }) { c }
        controller.refresh(); awaitIdle(controller)
        controller.selectSpace("room"); awaitIdle(controller)
        controller.draft("Keep this draft")
        controller.send(); awaitIdle(controller)
        assertEquals("Keep this draft", controller.state.value.draft)
        assertTrue(controller.state.value.error.isNotBlank())
        failSend = false
        controller.send(); awaitIdle(controller)
        assertEquals(sent[0].getString("event_id"), sent[1].getString("event_id"))
        assertEquals("", controller.state.value.draft)
        controller.draft("Room A draft")
        controller.selectSpace("other"); awaitIdle(controller)
        assertEquals("", controller.state.value.draft)
        controller.selectSpace("room"); awaitIdle(controller)
        assertEquals("Room A draft", controller.state.value.draft)
        controller.home()
        assertEquals("", controller.state.value.selectedSpace)
        assertEquals("", controller.state.value.draft)
        controller.selectSpace("room"); awaitIdle(controller)
        assertEquals("Room A draft", controller.state.value.draft)
        controller.clear()
        assertTrue(controller.state.value.events.isEmpty())
    }
    @Test fun refreshDropsRevokedRoomAndRebindsAccountWithoutLeakingRootProfile() = runBlocking {
        var roomsVisible = true
        val profiles = mutableListOf<String>()
        fun account(owner: String, profile: String) = CypherClient(AuthContext("https://native.example", emptyList(), owner, profile), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                profiles += request.url.queryParameter("profile").orEmpty()
                val result = if (request.url.encodedPath.endsWith("/space/room"))
                    JSONObject("""{"room":{"room_id":"room"},"events":[{"event_id":"1","kind":"message.user","payload":{"text":"Private history"}}]}""")
                else JSONObject(if (roomsVisible) """{"bots":[],"spaces":[{"room_id":"room","name":"Team"}]}""" else """{"bots":[],"spaces":[]}""")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Native").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        var live = account("owner", "default")
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { live }
        controller.refresh(); awaitIdle(controller)
        controller.selectSpace("room"); awaitIdle(controller)
        controller.draft("Keep privately")
        assertEquals("Private history", controller.state.value.events.single().text)
        // ViewModel replaces its client during an internal bot switch. Shared space stays at its root.
        live = account("owner", "writer")
        controller.refresh(); awaitIdle(controller)
        assertEquals("default", profiles.last())
        assertEquals("Keep privately", controller.state.value.draft)
        roomsVisible = false
        controller.refresh(); awaitIdle(controller)
        assertTrue(controller.state.value.events.isEmpty())
        assertTrue(controller.state.value.actions.isEmpty())
        assertEquals("", controller.state.value.selectedSpace)
        assertEquals("", controller.state.value.draft)
        roomsVisible = true
        controller.selectSpace("room"); awaitIdle(controller)
        assertEquals("Keep privately", controller.state.value.draft)
        live = account("another-owner", "writer")
        controller.refresh(); awaitIdle(controller)
        assertEquals("writer", profiles.last())
        assertTrue(controller.state.value.events.isEmpty())
        assertEquals("", controller.state.value.draft)
        controller.clear()
    }

    @Test fun nativeFailedTurnsShowSanitizedProviderReason() {
        val events = botModeEvents(JSONObject("""{"events":[{"event_id":"failed-1","kind":"turn.failed","payload":{"member_id":"cybercb","error":"HTTP 402 insufficient balance secret-token-do-not-display","reason_code":"provider_quota_limit"}}]}"""))
        assertTrue(events.single().failed)
        assertEquals("cybercb", events.single().speaker)
        assertTrue(events.single().text.contains("balance or quota"))
        assertFalse(events.single().text.contains("secret-token"))
    }
    @Test fun draftBeforeAuthorizationNeverWritesNullScope() {
        val saved = SavedStateHandle()
        val controller = NativeBotModeController(CoroutineScope(Dispatchers.Unconfined), saved, { _, _ -> }) { null }
        controller.draft("Not scoped")
        assertTrue(saved.keys().isEmpty())
        assertTrue(controller.state.value.error.isNotBlank())
    }

    @Test fun backInvalidatesDelayedCanonicalOpenAndKeepsSharedDraft() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var opened = false
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val result = when {
                    request.url.encodedPath.endsWith("/chat") -> {
                        started.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        JSONObject("""{"profile":"writer","title":"Bot Chat","stored_session_id":"canonical"}""")
                    }
                    request.url.encodedPath.endsWith("/space/room") -> JSONObject("""{"room":{"room_id":"room"},"events":[]}""")
                    else -> JSONObject("""{"bots":[],"spaces":[{"room_id":"room","name":"Team"}]}""")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Native").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> opened = true }) { c }
        controller.refresh(); awaitIdle(controller)
        controller.selectSpace("room"); awaitIdle(controller)
        controller.draft("Retain shared draft")
        controller.openBot(BotModeBot("writer", "Writer", ""))
        yield()
        withContext(Dispatchers.IO) { check(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        val openingJobs = coroutineContext[Job]!!.children.toList()
        controller.back()
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.sending)
        assertNull(controller.state.value.activeBot)
        release.countDown()
        openingJobs.forEach { it.join() }
        controller.refresh(); awaitIdle(controller)
        assertFalse(opened)
        assertNull(controller.state.value.activeBot)
        assertEquals("", controller.state.value.sessionId)
        assertEquals("Retain shared draft", controller.state.value.draft)
        assertEquals("room", controller.state.value.selectedSpace)
        controller.clear()
    }

}
