package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.core.visibleSessionRows
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * History on stock Hermes, with the optional Cypher plugin absent or failing.
 *
 * Every response below is a SYNTHETIC FIXTURE served by an interceptor. It stands in for the stock dashboard routes
 * the Hermes Relay reference uses (DashboardApiClient.listSessions / getSessionMessages):
 *  - GET /api/sessions?profile=&archived=exclude|only|include&exclude_sources=cron&min_messages=1&limit<=100&offset=   (Cypher keeps a 200-row window: 2x100)
 *  - GET /api/sessions/{id}?profile=                                                (metadata)
 *  - GET /api/sessions/{id}/messages?profile=&limit<=500&offset=&order=latest|oldest
 *    -> {session_id (the resolved compression descendant), profile, messages (chronological, ancestors included),
 *        pagination:{limit, offset, order, returned}}
 *  - GET /api/sessions/{id}/latest-descendant?profile= -> {requested_session_id, session_id, path:[root..tip], changed}
 * No content, identity or timing here comes from a real server.
 */
class NativeStockHistoryTest {
    /** Passive browsing must never open the native socket (no session.resume/history just to read). */
    private class SocketProbe : WebSocket.Factory {
        val opened = CopyOnWriteArrayList<Request>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            opened += request
            throw IOException("fixture: passive history must not open the native socket")
        }
    }

    private class Stock {
        var active: List<JSONObject> = rows("active", 3)
        var archived: List<JSONObject> = rows("archived", 2)
        var messages: List<JSONObject> = listOf(
            json("id" to 41L, "role" to "user", "content" to "fixture question", "timestamp" to 1_000L),
            json("role" to "assistant", "content" to "fixture answer", "tool_calls" to JSONArray().put(json("id" to "call-1", "name" to "terminal"))),
            json("role" to "tool", "content" to "fixture tool output", "tool_call_id" to "call-1", "timestamp" to 1_002L),
        )
        var listEnvelope = "sessions"
        /** Simulates a row moving between pages: page 2 starts one row early. */
        var shiftSecondPage = false
        var pluginStatus = 500
        /** When set, `stored-a` was compressed: /messages resolves to this descendant (ancestors included). */
        var compressedTip: String? = null
        /** Overrides the latest-descendant chain body for `stored-a`. */
        var chain: JSONObject? = null
        /** The optional voice archive never answers until its call is cancelled. */
        var hangVoice = false
        val voiceStarted = java.util.concurrent.CountDownLatch(1)
        @Volatile var voiceCancelled = false
        /** Path-specific override: (status, raw body). */
        var override: (Request) -> Pair<Int, String>? = { null }
        val requests = CopyOnWriteArrayList<Request>()

        fun respond(request: Request): Pair<Int, String> {
            requests += request
            override(request)?.let { return it }
            val url = request.url
            val path = url.encodedPath
            if (path.startsWith("/api/plugins/")) return pluginStatus to json("detail" to "fixture optional plugin failure").toString()
            val profile = url.queryParameter("profile")
            if (path == "/api/sessions") {
                val limit = url.queryParameter("limit")?.toIntOrNull() ?: 20
                if (limit !in 1..100) return 422 to json("detail" to "limit must be <= 100").toString()
                val minMessages = url.queryParameter("min_messages")?.toIntOrNull() ?: 0
                val rows = when (url.queryParameter("archived") ?: "exclude") {
                    "exclude" -> active; "only" -> archived; "include" -> active + archived
                    else -> return 422 to json("detail" to "bad archived").toString()
                }.filter { it.optInt("message_count") >= minMessages }
                var offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
                if (shiftSecondPage && offset > 0) offset -= 1
                val page = rows.drop(offset).take(limit)
                return 200 to json(listEnvelope to JSONArray(page), "total" to rows.size).toString()
            }
            val match = Regex("/api/sessions/([^/]+)(/messages|/latest-descendant)?").matchEntire(path) ?: return 404 to json("detail" to "Not Found").toString()
            val id = match.groupValues[1]
            if ((active + archived).none { it.getString("id") == id } && id != "stored-a" && id != "empty-draft") return 404 to json("detail" to "Session not found").toString()
            val all = if (id == "empty-draft") emptyList() else messages
            val tip = compressedTip?.takeIf { id == "stored-a" }
            if (match.groupValues[2].isEmpty()) {
                return 200 to json("id" to id, "session_id" to id, "profile" to profile, "title" to "Fixture title", "message_count" to all.size).toString()
            }
            if (match.groupValues[2] == "/latest-descendant") {
                chain?.takeIf { id == "stored-a" }?.let { return 200 to it.toString() }
                return 200 to (if (tip == null) json("requested_session_id" to id, "session_id" to id, "path" to JSONArray().put(id), "changed" to false)
                    else json("requested_session_id" to id, "session_id" to tip, "path" to JSONArray().put(id).put("stored-a-mid").put(tip), "changed" to true)).toString()
            }
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 100
            if (limit !in 1..500) return 422 to json("detail" to "limit must be <= 500").toString()
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            val order = url.queryParameter("order") ?: "oldest"
            // `latest` is the newest window; this fixture returns that window in chronological order.
            val window = if (order == "latest") all.dropLast(offset).takeLast(limit) else all.drop(offset).take(limit)
            return 200 to json(
                "session_id" to (tip ?: id), "profile" to profile, "messages" to JSONArray(window),
                "pagination" to json("limit" to limit, "offset" to offset, "order" to order, "returned" to window.size),
            ).toString()
        }

        companion object {
            fun rows(prefix: String, count: Int, profile: String? = "work") = (0 until count).map {
                json("id" to "$prefix-$it", "title" to "$prefix title $it", "preview" to "secret preview words", "message_count" to 2)
                    .apply { profile?.let { p -> put("profile", p) } }
            }
        }
    }

    private fun client(stock: Stock, probe: SocketProbe = SocketProbe()) =
        CypherClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, probe) { builder ->
            builder.addInterceptor { chain ->
                if (stock.hangVoice && chain.request().url.encodedPath.endsWith("/voice-history")) {
                    // Behaves like a stalled network read: it ends only when OkHttp cancels the call.
                    stock.requests += chain.request(); stock.voiceStarted.countDown()
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
                    stock.voiceCancelled = chain.call().isCanceled()
                    throw IOException("fixture: optional voice archive never answered")
                }
                val (code, body) = stock.respond(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

    private fun Stock.native() = requests.filterNot { it.url.encodedPath.startsWith("/api/plugins/") }
    private fun Stock.pluginHistory() = requests.filter { it.url.encodedPath.startsWith("/api/plugins/cypher/mobile/sessions") && !it.url.encodedPath.endsWith("/voice-history") }
    private fun Stock.listPages() = requests.filter { it.url.encodedPath == "/api/sessions" }
    private fun ids(result: JSONObject) = (0 until result.getJSONArray("sessions").length()).map { result.getJSONArray("sessions").getJSONObject(it).getString("session_id") }
    private fun assertProfiled(stock: Stock) {
        assertTrue("Every native read is scoped to the verified profile", stock.native().isNotEmpty() && stock.native().all { it.url.queryParameter("profile") == "work" })
    }
    private suspend fun list(c: CypherClient, archived: Boolean = false, query: String = "") =
        if (query.isBlank()) c.get("/api/sessions", mapOf("profile" to "work", "include_archived" to if (archived) "1" else "0"))
        else c.get("/api/sessions/search", mapOf("profile" to "work", "include_archived" to if (archived) "1" else "0", "q" to query))

    @Test fun activeListUsesStockSessionsWithoutPluginOrSocket() = runBlocking<Unit> {
        val stock = Stock(); val probe = SocketProbe(); val c = client(stock, probe)
        val result = list(c)
        assertEquals(listOf("active-0", "active-1", "active-2"), ids(result))
        for (i in 0 until 3) assertFalse(result.getJSONArray("sessions").getJSONObject(i).getBoolean("archived"))
        assertEquals("active title 1", result.getJSONArray("sessions").getJSONObject(1).getString("title"))
        assertEquals(200, result.getInt("limit"))
        assertEquals("Newest 200 conversation titles and identities", result.getString("search_scope"))
        assertTrue(stock.pluginHistory().isEmpty())
        assertTrue(stock.listPages().all { it.url.queryParameter("archived") in setOf(null, "exclude") })
        assertTrue("Cron runs are excluded from active recents", stock.listPages().all { it.url.queryParameter("exclude_sources") == "cron" })
        assertTrue("Zero-message sessions are excluded from active recents", stock.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertTrue(stock.listPages().all { (it.url.queryParameter("limit")?.toInt() ?: 0) in 1..100 })
        assertProfiled(stock)
        assertTrue(probe.opened.isEmpty())
    }

    @Test fun emptySessionsAreFilteredAndConfirmedShellOverlaySurvivesRefresh() = runBlocking<Unit> {
        val stock = Stock().apply { active = Stock.rows("active", 2) + json("id" to "old-empty", "title" to "Untitled conversation", "message_count" to 0) }
        val result = list(client(stock))
        assertEquals(listOf("active-0", "active-1"), ids(result))
        val confirmedShell = json("session_id" to "phone-created", "title" to "New conversation", "message_count" to 0, "profile" to "work")
        val refreshed = visibleSessionRows(result.rows("sessions"), listOf(confirmedShell), "", archived = false)
        assertEquals(listOf("phone-created", "active-0", "active-1"), refreshed.map(::sessionRowId))
        assertTrue("Every stock recents page requests the minimum message count", stock.listPages().all { it.url.queryParameter("min_messages") == "1" })
    }

    @Test fun archivedListContainsOnlyArchivedConversations() = runBlocking<Unit> {
        val stock = Stock(); val c = client(stock)
        val result = list(c, archived = true)
        assertEquals(listOf("archived-0", "archived-1"), ids(result))
        for (i in 0 until 2) assertTrue(result.getJSONArray("sessions").getJSONObject(i).getBoolean("archived"))
        assertTrue(stock.listPages().isNotEmpty() && stock.listPages().all { it.url.queryParameter("archived") == "only" })
        assertTrue("Cron runs are excluded from archived recents", stock.listPages().all { it.url.queryParameter("exclude_sources") == "cron" })
        assertTrue("Zero-message sessions are excluded from archived recents", stock.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertProfiled(stock)
    }

    @Test fun windowIsTwoBoundedPagesStopsEarlyAndDeduplicates() = runBlocking<Unit> {
        val full = Stock().apply { active = Stock.rows("row", 250) }
        val fullResult = list(client(full))
        assertEquals(200, ids(fullResult).size)
        assertEquals(listOf("0", "100"), full.listPages().map { it.url.queryParameter("offset") ?: "0" })
        assertTrue(full.listPages().all { it.url.queryParameter("limit") == "100" })

        val partial = Stock().apply { active = Stock.rows("row", 150) }
        assertEquals(150, ids(list(client(partial))).size)
        assertEquals(2, partial.listPages().size)

        val small = Stock().apply { active = Stock.rows("row", 40) }
        assertEquals(40, ids(list(client(small))).size)
        assertEquals("A short first page ends the window", 1, small.listPages().size)

        val shifted = Stock().apply { active = Stock.rows("row", 250); shiftSecondPage = true }
        val deduplicated = ids(list(client(shifted)))
        assertEquals(deduplicated.distinct(), deduplicated)
        assertEquals(199, deduplicated.size)
        assertEquals("Never a third page", 2, shifted.listPages().size)
        assertProfiled(shifted)
    }

    @Test fun drawerFiltersRunInHermesSoTheWindowHoldsOnlyWhatIsShown() = runBlocking<Unit> {
        val hidden = Stock(); val c = client(hidden)
        c.get("/api/sessions", mapOf("profile" to "work", "include_archived" to "0", "hidden_sources" to "telegram,webui,Bad Name;x", "order" to "created"))
        assertTrue("Hidden sources join cron; malformed names are never sent", hidden.listPages().all { it.url.queryParameter("exclude_sources") == "cron,telegram,webui" })
        assertTrue(hidden.listPages().all { it.url.queryParameter("order") == "created" })

        val threads = Stock(); val t = client(threads)
        t.get("/api/sessions", mapOf("profile" to "work", "include_archived" to "0", "view" to "threads", "hidden_sources" to "telegram"))
        assertTrue("The Threads lane asks Hermes for Cypher and Relay threads only", threads.listPages().all { it.url.queryParameter("sources") == "android_thread,phone" && it.url.queryParameter("exclude_sources") == null })
        assertTrue("Recent activity is the default order", threads.listPages().all { it.url.queryParameter("order") == "recent" })
        assertTrue(threads.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertProfiled(threads)
    }

    @Test fun searchMatchesOnlyTitleAndIdentityWithinTheWindow() = runBlocking<Unit> {
        val stock = Stock(); val c = client(stock)
        assertEquals(listOf("active-1"), ids(list(c, query = "ACTIVE TITLE 1")))
        assertEquals(listOf("active-2"), ids(list(c, query = "active-2")))
        assertEquals("Preview/content is not searchable", 0, ids(list(c, query = "secret preview")).size)
        assertEquals(listOf("archived-0"), ids(list(c, archived = true, query = "archived-0")))
        assertTrue(stock.pluginHistory().isEmpty())
        assertTrue(stock.listPages().all { (it.url.queryParameter("limit")?.toInt() ?: 0) in 1..100 })
        assertTrue("List and local-search requests both exclude cron runs", stock.listPages().all { it.url.queryParameter("exclude_sources") == "cron" })
        assertTrue("List and local-search requests exclude zero-message sessions", stock.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertProfiled(stock)
    }

    @Test fun listAcceptsStockEnvelopeVariants() = runBlocking<Unit> {
        for (envelope in listOf("sessions", "items", "data")) {
            val stock = Stock().apply { listEnvelope = envelope }
            assertEquals(envelope, listOf("active-0", "active-1", "active-2"), ids(list(client(stock))))
        }
    }

    @Test fun foreignProfileIsRejectedExplicitly() = runBlocking<Unit> {
        val stock = Stock(); val c = client(stock)
        assertTrue(runCatching { c.get("/api/sessions", mapOf("profile" to "other")) }.isFailure)
        assertTrue(runCatching { c.get("/api/session", mapOf("session_id" to "stored-a", "profile" to "other")) }.isFailure)
        assertTrue("Rejected before any request", stock.requests.isEmpty())
        val foreignRows = Stock().apply { active = Stock.rows("active", 2) + Stock.rows("foreign", 1, profile = "other") }
        assertTrue("A row explicitly owned by another profile fails closed", runCatching { list(client(foreignRows)) }.isFailure)
    }

    @Test fun optionalPluginFailureNeverBreaksNativeHistory() = runBlocking<Unit> {
        for (status in listOf(401, 404, 500)) {
            val stock = Stock().apply { pluginStatus = status }; val probe = SocketProbe(); val c = client(stock, probe)
            assertEquals("plugin $status", 3, ids(list(c)).size)
            val transcript = c.get("/api/session", mapOf("session_id" to "stored-a"))
            assertEquals("plugin $status", "stored-a", transcript.getString("session_id"))
            assertEquals(3, transcript.getJSONArray("messages").length())
            assertFalse(transcript.optBoolean("voice_transcripts_available", false))
            assertTrue(stock.pluginHistory().isEmpty())
            assertTrue(probe.opened.isEmpty())
        }
    }

    @Test fun nativeFailuresAndMalformedResponsesAreErrorsNotEmptySuccess() = runBlocking<Unit> {
        fun failing(path: Regex, code: Int, body: String) = Stock().apply { override = { if (path.matches(it.url.encodedPath)) code to body else null } }
        val listPath = Regex("/api/sessions")
        for (code in listOf(401, 403, 500)) {
            val error = runCatching { list(client(failing(listPath, code, json("detail" to "fixture").toString()))) }.exceptionOrNull()
            assertTrue("list $code: $error", error is ApiFailure && error.status == code)
        }
        for (body in listOf("""{"unexpected":true}""", """{"sessions":"not-a-list"}""", "<html>login</html>")) {
            assertTrue("malformed list $body", runCatching { list(client(failing(listPath, 200, body))) }.isFailure)
        }
        val messagesPath = Regex("/api/sessions/stored-a/messages")
        for (code in listOf(401, 404, 500)) {
            val error = runCatching { client(failing(messagesPath, code, json("detail" to "fixture").toString())).get("/api/session", mapOf("session_id" to "stored-a")) }.exceptionOrNull()
            assertTrue("messages $code: $error", error is ApiFailure && error.status == code)
        }
        for (body in listOf("""{"session_id":"stored-a","profile":"work"}""", """{"session_id":"stored-a","profile":"work","messages":"x"}""", "<html>login</html>")) {
            assertTrue("malformed transcript $body", runCatching { client(failing(messagesPath, 200, body)).get("/api/session", mapOf("session_id" to "stored-a")) }.isFailure)
        }
        val missing = runCatching { client(Stock()).get("/api/session", mapOf("session_id" to "missing-session")) }.exceptionOrNull()
        assertTrue("An unknown conversation stays a 404 for the caller's missing-conversation path", missing is ApiFailure && missing.status == 404)
    }

    @Test fun passiveTranscriptProjectsStockMessagesWithoutResumingAnAgent() = runBlocking<Unit> {
        val stock = Stock(); val probe = SocketProbe(); val c = client(stock, probe)
        val transcript = c.get("/api/session", mapOf("session_id" to "stored-a"))
        assertEquals("stored-a", transcript.getString("session_id"))
        assertEquals("work", transcript.getString("profile"))
        assertEquals("Fixture title", transcript.getString("title"))
        val messages = transcript.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "tool"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals(listOf("fixture question", "fixture answer", "fixture tool output"), (0 until messages.length()).map { messages.getJSONObject(it).getString("content") })
        assertEquals("call-1", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("terminal", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("name"))
        assertEquals(1_000L, messages.getJSONObject(0).getLong("timestamp"))
        assertFalse("A missing time stays unknown", messages.getJSONObject(1).has("timestamp") && !messages.getJSONObject(1).isNull("timestamp"))
        assertTrue("No runtime identity is invented", transcript.optString("runtime_session_id").isBlank())
        assertTrue("Browsing is not a live turn", transcript.optString("active_stream_id").isBlank())
        assertEquals("stored-a", transcript.getString("stored_session_id"))
        assertEquals("The dashboard's numeric row id is preserved as the gateway row_id", 41, messages.getJSONObject(0).opt("row_id"))
        assertFalse("A message without a dashboard id gets no row id", messages.getJSONObject(1).has("row_id"))
        assertFalse(transcript.getBoolean("history_truncated"))
        assertTrue("An unchanged id needs no descendant lookup", stock.requests.none { it.url.encodedPath.endsWith("/latest-descendant") })
        val reads = stock.requests.filter { it.url.encodedPath == "/api/sessions/stored-a/messages" }
        assertEquals("One bounded newest window", 1, reads.size)
        assertEquals("latest", reads.single().url.queryParameter("order"))
        assertEquals("500", reads.single().url.queryParameter("limit"))
        assertEquals("0", reads.single().url.queryParameter("offset") ?: "0")
        assertTrue(stock.pluginHistory().isEmpty())
        assertProfiled(stock)
        assertTrue("No session.resume/history socket for passive browsing", probe.opened.isEmpty())
    }

    @Test fun newestWindowIsBoundedAndSaysSo() = runBlocking<Unit> {
        val stock = Stock().apply { messages = (0 until 650).map { json("role" to if (it % 2 == 0) "user" else "assistant", "content" to "m$it") } }
        val transcript = client(stock).get("/api/session", mapOf("session_id" to "stored-a"))
        val messages = transcript.getJSONArray("messages")
        assertEquals(500, messages.length())
        assertEquals("m150", messages.getJSONObject(0).getString("content"))
        assertEquals("m649", messages.getJSONObject(499).getString("content"))
        assertTrue("A full window may hide older messages", transcript.getBoolean("history_truncated"))
        assertEquals(500, transcript.getInt("history_limit"))
        assertEquals(1, stock.requests.count { it.url.encodedPath == "/api/sessions/stored-a/messages" })
    }

    @Test fun emptyDraftShellStaysEmptyAndUntruncated() = runBlocking<Unit> {
        val transcript = client(Stock()).get("/api/session", mapOf("session_id" to "empty-draft"))
        assertEquals("empty-draft", transcript.getString("session_id"))
        assertEquals(0, transcript.getJSONArray("messages").length())
        assertFalse(transcript.getBoolean("history_truncated"))
        assertTrue(transcript.optString("active_stream_id").isBlank())
    }

    @Test fun compressedHistoryKeepsTheRequestedIdentityAndIncludesAncestors() = runBlocking<Unit> {
        val stock = Stock().apply {
            compressedTip = "stored-a-tip"
            messages = listOf(
                json("id" to 1L, "session_id" to "stored-a", "role" to "user", "content" to "ancestor question before compression"),
                json("id" to 2L, "session_id" to "stored-a", "role" to "assistant", "content" to "ancestor answer"),
                json("id" to 9L, "session_id" to "stored-a-tip", "role" to "user", "content" to "question after compression"),
            )
        }
        val probe = SocketProbe()
        val transcript = client(stock, probe).get("/api/session", mapOf("session_id" to "stored-a"))
        assertEquals("The UI keeps the conversation it asked for", "stored-a", transcript.getString("session_id"))
        assertEquals("The resolved descendant is exposed deliberately", "stored-a-tip", transcript.getString("stored_session_id"))
        val messages = transcript.getJSONArray("messages")
        assertEquals(listOf("ancestor question before compression", "ancestor answer", "question after compression"), (0 until messages.length()).map { messages.getJSONObject(it).getString("content") })
        assertTrue(transcript.optString("runtime_session_id").isBlank())
        val chains = stock.requests.filter { it.url.encodedPath == "/api/sessions/stored-a/latest-descendant" }
        assertEquals("One chain check, only because the id changed", 1, chains.size)
        assertProfiled(stock)
        assertTrue(probe.opened.isEmpty())
    }

    @Test fun aChangedIdWithoutAProvenChainIsRejected() = runBlocking<Unit> {
        fun descendants(path: List<String>, session: String = "stored-a-tip", requested: String = "stored-a", changed: Any = true, profile: String? = null) =
            json("requested_session_id" to requested, "session_id" to session, "path" to JSONArray(path), "changed" to changed).apply { profile?.let { put("profile", it) } }
        val broken = mapOf(
            "chain ends elsewhere" to descendants(listOf("stored-a", "stored-a-mid", "other-tip"), session = "other-tip"),
            "returned target differs" to descendants(listOf("stored-a", "stored-a-tip"), session = "other-tip"),
            "path starts elsewhere" to descendants(listOf("other-root", "stored-a-tip")),
            "requested id differs" to descendants(listOf("stored-a", "stored-a-tip"), requested = "other-root"),
            "not marked changed" to descendants(listOf("stored-a", "stored-a-tip"), changed = false),
            "foreign profile" to descendants(listOf("stored-a", "stored-a-tip"), profile = "other"),
            "single entry" to descendants(listOf("stored-a-tip")),
            "empty path" to descendants(emptyList()),
            "unsafe entry" to descendants(listOf("stored-a", "../auth/me", "stored-a-tip")),
            "cycle" to descendants(listOf("stored-a", "stored-a-tip", "stored-a", "stored-a-tip")),
            "unbounded" to descendants(listOf("stored-a") + (1..600).map { "mid-$it" } + "stored-a-tip"),
        )
        for ((reason, body) in broken) {
            val stock = Stock().apply { compressedTip = "stored-a-tip"; chain = body }
            assertTrue(reason, runCatching { client(stock).get("/api/session", mapOf("session_id" to "stored-a")) }.isFailure)
        }
        val missing = Stock().apply { compressedTip = "stored-a-tip"; override = { if (it.url.encodedPath.endsWith("/latest-descendant")) 404 to json("detail" to "fixture").toString() else null } }
        val error = runCatching { client(missing).get("/api/session", mapOf("session_id" to "stored-a")) }.exceptionOrNull()
        assertTrue("No chain endpoint means no proven chain: $error", error is ApiFailure && error.status == 404)
        val explicit = Stock().apply { compressedTip = "stored-a-tip"; chain = descendants(listOf("stored-a", "stored-a-tip"), profile = "work") }
        assertEquals("stored-a-tip", client(explicit).get("/api/session", mapOf("session_id" to "stored-a")).getString("stored_session_id"))
    }

    @Test fun aStalledVoiceArchiveIsBoundedWhileExternalCancellationStillCancels() = runBlocking<Unit> {
        val stalled = Stock().apply { hangVoice = true }
        val started = System.nanoTime()
        val transcript = client(stalled).get("/api/session", mapOf("session_id" to "stored-a"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(3, transcript.getJSONArray("messages").length())
        assertFalse(transcript.getBoolean("voice_transcripts_available"))
        assertTrue("The stalled optional read was cancelled", stalled.voiceCancelled)
        assertTrue("Native history is not held for the optional read: $elapsedMs ms", elapsedMs < 6_000)

        val held = Stock().apply { hangVoice = true }
        val c = client(held)
        val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try { c.get("/api/session", mapOf("session_id" to "stored-a")); outcome.complete(null) }
            catch (e: Throwable) { outcome.complete(e); throw e }
        }
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { held.voiceStarted.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        job.cancel()
        val failure = kotlinx.coroutines.withTimeout(6_000) { outcome.await() }
        assertTrue("A caller's cancellation is not turned into an unavailable-voice success: $failure", failure is kotlinx.coroutines.CancellationException)
        kotlinx.coroutines.withTimeout(6_000) { job.join() }
    }

    @Test fun transcriptRejectsForeignIdentityProfileAndUnsafeIds() = runBlocking<Unit> {
        fun rewriting(field: String, value: String) = Stock().apply {
            override = { request ->
                if (request.url.encodedPath == "/api/sessions/stored-a/messages") {
                    200 to json("session_id" to "stored-a", "profile" to "work", "messages" to JSONArray(), "pagination" to json("limit" to 500, "offset" to 0, "order" to "latest", "returned" to 0)).put(field, value).toString()
                } else null
            }
        }
        assertTrue("Another conversation's transcript is rejected", runCatching { client(rewriting("session_id", "other-session")).get("/api/session", mapOf("session_id" to "stored-a")) }.isFailure)
        assertTrue("Another profile's transcript is rejected", runCatching { client(rewriting("profile", "other")).get("/api/session", mapOf("session_id" to "stored-a")) }.isFailure)
        for (unsafe in listOf("../auth/me", "a/b", "", "x".repeat(300))) {
            val stock = Stock()
            assertTrue("unsafe id $unsafe", runCatching { client(stock).get("/api/session", mapOf("session_id" to unsafe)) }.isFailure)
            assertTrue(stock.requests.isEmpty())
        }
    }
}
