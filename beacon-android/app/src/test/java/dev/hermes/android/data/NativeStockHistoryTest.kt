package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.core.visibleSessionRows
import dev.hermes.android.core.resolveAttachmentReference
import dev.hermes.android.core.NativeReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * History on stock Hermes, with the optional Beacon plugin absent or failing.
 *
 * Every response below is a SYNTHETIC FIXTURE served by an interceptor. It stands in for the stock dashboard routes
 * the Hermes Relay reference uses (DashboardApiClient.listSessions / getSessionMessages):
 *  - GET /api/sessions?profile=&archived=exclude|only|include&exclude_sources=cron&min_messages=1&limit<=100&offset=   (Beacon keeps a 200-row window: 2x100)
 *  - GET /api/sessions/{id}?profile=                                                (metadata)
 *  - GET /api/sessions/{id}/messages?profile=&limit<=500&offset=&order=latest|oldest
 *    -> {session_id (the resolved compression descendant), profile, messages (chronological, ancestors included),
 *        pagination:{limit, offset, order, returned}}
 *  - GET /api/sessions/{id}/latest-descendant?profile= -> {requested_session_id, session_id, path:[root..tip], changed}
 * No content, identity or timing here comes from a real server.
 */
class NativeStockHistoryTest {
    /** Passive browsing must never open the native socket (no session.resume/history just to read). */
    private class SocketProbe(private val archiveListing: Boolean = false) : WebSocket.Factory {
        val opened = CopyOnWriteArrayList<Request>()
        val methods = CopyOnWriteArrayList<String>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            opened += request
            if (!archiveListing) throw IOException("fixture: passive history must not open the native socket")
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    val method = frame.getString("method"); methods += method
                    check(method in setOf("client.capabilities", "session.list")) { "Archive browsing must not resume or create agents" }
                    val result = if (method == "session.list") json("sessions" to JSONArray()) else json()
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            return socket
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
            if (path == "/api/auth/ws-ticket") return 200 to json("ticket" to "fixture-ticket").toString()
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
            val match = Regex("/api/sessions/([^/]+)(/messages(?:/around)?|/latest-descendant)?").matchEntire(path) ?: return 404 to json("detail" to "Not Found").toString()
            val id = match.groupValues[1]
            if ((active + archived).none { it.getString("id") == id } && id != "stored-a" && id != "empty-draft") return 404 to json("detail" to "Session not found").toString()
            val all = if (id == "empty-draft") emptyList() else messages
            val tip = compressedTip?.takeIf { id == "stored-a" }
            if (match.groupValues[2].isEmpty()) {
                return 200 to json("id" to id, "session_id" to id, "profile" to profile, "title" to "Fixture title", "message_count" to all.size, "last_active" to all.size).toString()
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
            val window = if (path.endsWith("/around")) all.filter { it.optLong("id") == url.queryParameter("row_id")?.toLong() }.take(limit)
                else if (order == "latest") all.dropLast(offset).takeLast(limit) else all.drop(offset).take(limit)
            val projected = window.map { row ->
                if (url.queryParameter("inline_images") != "false" || row.opt("content") !is JSONArray) row else {
                    val content = row.getJSONArray("content")
                    val text = (0 until content.length()).joinToString("") { i ->
                        val part = content.getJSONObject(i)
                        if (part.optString("type") == "text") part.optString("text") else "\n[image]"
                    }
                    JSONObject(row.toString()).put("content", text)
                }
            }
            return 200 to json(
                "session_id" to (tip ?: id), "profile" to profile, "messages" to JSONArray(projected),
                "pagination" to json("limit" to limit, "offset" to offset, "order" to order, "returned" to window.size),
            ).toString()
        }

        companion object {
            fun rows(prefix: String, count: Int, profile: String? = "work") = (0 until count).map {
                json("id" to "$prefix-$it", "title" to "$prefix title $it", "preview" to "secret preview words", "message_count" to 2)
                    .apply { put("archived", prefix == "archived"); profile?.let { p -> put("profile", p) } }
            }
        }
    }

    private fun client(stock: Stock, probe: SocketProbe = SocketProbe()) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, probe) { builder ->
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
    private fun Stock.pluginHistory() = requests.filter { it.url.encodedPath.startsWith("/api/plugins/beacon/mobile/sessions") && !it.url.encodedPath.endsWith("/voice-history") }
    private fun Stock.listPages() = requests.filter { it.url.encodedPath == "/api/sessions" }
    private fun ids(result: JSONObject) = (0 until result.getJSONArray("sessions").length()).map { result.getJSONArray("sessions").getJSONObject(it).getString("session_id") }
    private fun assertProfiled(stock: Stock) {
        assertTrue("Every history read is scoped to the verified profile", stock.native().isNotEmpty() && stock.native().filter { it.url.encodedPath.startsWith("/api/sessions") }.all { it.url.queryParameter("profile") == "work" })
    }
    private suspend fun list(c: BeaconClient, archived: Boolean = false, query: String = "") =
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
        val stock = Stock(); val probe = SocketProbe(archiveListing = true); val c = client(stock, probe)
        val result = list(c, archived = true)
        assertEquals(listOf("archived-0", "archived-1"), ids(result))
        for (i in 0 until 2) assertTrue(result.getJSONArray("sessions").getJSONObject(i).getBoolean("archived"))
        assertTrue(stock.listPages().isNotEmpty() && stock.listPages().all { it.url.queryParameter("archived") == "only" })
        assertTrue("Cron runs are excluded from archived recents", stock.listPages().all { it.url.queryParameter("exclude_sources") == "cron" })
        assertTrue("Zero-message sessions are excluded from archived recents", stock.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertProfiled(stock)
        assertEquals(2, probe.methods.count { it == "session.list" })
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
        assertTrue("The Threads lane asks Hermes for Beacon and Relay threads only", threads.listPages().all { it.url.queryParameter("sources") == "android_thread,phone" && it.url.queryParameter("exclude_sources") == null })
        assertTrue("Recent activity is the default order", threads.listPages().all { it.url.queryParameter("order") == "recent" })
        assertTrue(threads.listPages().all { it.url.queryParameter("min_messages") == "1" })
        assertProfiled(threads)
    }

    @Test fun searchMatchesOnlyTitleAndIdentityWithinTheWindow() = runBlocking<Unit> {
        val stock = Stock(); val c = client(stock)
        assertEquals(listOf("active-1"), ids(list(c, query = "ACTIVE TITLE 1")))
        assertEquals(listOf("active-2"), ids(list(c, query = "active-2")))
        assertEquals("Preview/content is not searchable", 0, ids(list(c, query = "secret preview")).size)
        assertEquals(listOf("archived-0"), ids(list(client(stock, SocketProbe(archiveListing = true)), archived = true, query = "archived-0")))
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
        assertEquals("false", reads.single().url.queryParameter("inline_images"))
        assertTrue(stock.pluginHistory().isEmpty())
        assertProfiled(stock)
        assertTrue("No session.resume/history socket for passive browsing", probe.opened.isEmpty())
    }

    @Test fun photoHistoryBeyond32MiBKeepsTextCanonicalFilesAndLazyLegacyImages() = runBlocking {
        val image = "data:image/jpeg;base64," + "A".repeat(12 * 1024 * 1024)
        val stock = Stock().apply {
            messages = (1..3).map { i -> json("id" to i, "role" to "user", "content" to JSONArray()
                .put(json("type" to "text", "text" to "Photo $i\n@image:/workspace/photo-$i.jpg"))
                .put(json("type" to "image_url", "image_url" to json("url" to image)))) }
        }
        assertTrue("The original server page really exceeds the unchanged network cap", JSONArray(stock.messages).toString().toByteArray().size > 32 * 1024 * 1024)
        val c = client(stock)
        val history = c.get("/api/session", mapOf("session_id" to "stored-a"))
        assertTrue(history.toString().length < 10_000)
        assertEquals(3, history.getJSONArray("messages").length())
        for (i in 1..3) {
            val row = history.getJSONArray("messages").getJSONObject(i - 1)
            assertTrue(row.getString("content").contains("Photo $i"))
            val attachment = row.getJSONArray("attachments").getJSONObject(0)
            val saved = resolveAttachmentReference(attachment, c.scope.session("stored-a")) as NativeReference.HistoryImage
            assertEquals("/workspace/photo-$i.jpg", attachment.getString("path"))
            assertEquals("stored-a", saved.sessionId)
            assertEquals(i.toLong(), saved.rowId)
            assertEquals(0, saved.imageIndex)
        }
        stock.messages = listOf(json("id" to 77, "role" to "user", "content" to JSONArray()
            .put(json("type" to "text", "text" to "Old photo"))
            .put(json("type" to "image_url", "image_url" to json("url" to image)))))
        val legacy = c.get("/api/session", mapOf("session_id" to "stored-a"))
        val receipt = legacy.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments").getJSONObject(0)
        val ref = resolveAttachmentReference(receipt, c.scope.session("stored-a")) as NativeReference.HistoryImage
        assertEquals(77L, ref.rowId)
        val downloaded = c.historyImage(ref)
        assertEquals("image/jpeg", downloaded.mime)
        assertEquals(9 * 1024 * 1024, downloaded.bytes.size)
        val lazy = stock.requests.last()
        assertEquals("/api/sessions/stored-a/messages/around", lazy.url.encodedPath)
        assertEquals("77", lazy.url.queryParameter("row_id"))
        assertEquals("1", lazy.url.queryParameter("limit"))
        assertEquals("work", lazy.url.queryParameter("profile"))
    }

    @Test fun savedPhotoBytesWinOverUnchangedChangedOrMissingCanonicalFiles() = runBlocking {
        for (fileState in listOf("unchanged", "changed", "missing")) {
            val stock = Stock().apply {
                messages = listOf(json("id" to 77, "role" to "user", "content" to JSONArray()
                    .put(json("type" to "text", "text" to "Two photos\n@image:/workspace/first.png\n@image:/workspace/second.png"))
                    .put(json("type" to "image_url", "image_url" to json("url" to "data:image/png;base64,AQID")))
                    .put(json("type" to "image_url", "image_url" to json("url" to "data:image/jpeg;base64,BAUG")))))
                override = { request -> if (request.url.encodedPath == "/api/fs/download")
                    (if (fileState == "missing") 404 else 200) to (if (fileState == "changed") "replaced bytes" else "original bytes") else null }
            }
            val c = client(stock)
            val row = c.get("/api/session", mapOf("session_id" to "stored-a")).getJSONArray("messages").getJSONObject(0)
            val attachments = row.getJSONArray("attachments")
            assertEquals(fileState, 2, attachments.length())
            assertTrue("Saved bytes are fetched only when opened", stock.requests.none { it.url.encodedPath.endsWith("/around") })
            repeat(2) { index ->
                val ref = resolveAttachmentReference(attachments.getJSONObject(index), c.scope.session("stored-a")) as NativeReference.HistoryImage
                assertEquals(77L, ref.rowId); assertEquals(index, ref.imageIndex)
                val saved = c.historyImage(ref)
                assertArrayEquals(fileState, if (index == 0) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6), saved.bytes)
                assertEquals(if (index == 0) "image/png" else "image/jpeg", saved.mime)
            }
            assertTrue("Mutable files must not replace the saved photo: $fileState", stock.requests.none { it.url.encodedPath == "/api/fs/download" })
            assertProfiled(stock)
        }
    }

    @Test fun repeatedCanonicalPathKeepsEachSavedPhotoOccurrence() = runBlocking {
        val stock = Stock().apply {
            messages = listOf(json("id" to 77, "role" to "user", "content" to JSONArray()
                .put(json("type" to "text", "text" to "Two originals\n@image:/workspace/photo.png\n@image:/workspace/photo.png"))
                .put(json("type" to "image_url", "image_url" to json("url" to "data:image/png;base64,AQID")))
                .put(json("type" to "image_url", "image_url" to json("url" to "data:image/jpeg;base64,BAUG")))))
            override = { request -> if (request.url.encodedPath == "/api/fs/download") 200 to "replaced file bytes" else null }
        }
        val c = client(stock)
        val history = c.get("/api/session", mapOf("session_id" to "stored-a"))
        assertTrue("The transcript stays compact", history.toString().length < 2_000)
        assertFalse(history.toString().contains("base64"))
        assertEquals(500, history.getInt("history_limit"))
        val attachments = history.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments")
        assertEquals(2, attachments.length())
        val refs = (0 until attachments.length()).map { index ->
            val receipt = attachments.getJSONObject(index)
            assertEquals("/workspace/photo.png", receipt.getString("path"))
            assertEquals("photo.png", receipt.getString("name"))
            resolveAttachmentReference(receipt, c.scope.session("stored-a"))
        }
        assertEquals(listOf(NativeReference.HistoryImage("stored-a", 77, 0), NativeReference.HistoryImage("stored-a", 77, 1)), refs)
        assertTrue("Originals load only when opened", stock.requests.none { it.url.encodedPath.endsWith("/around") })
        refs.filterIsInstance<NativeReference.HistoryImage>().forEachIndexed { index, ref ->
            val saved = c.historyImage(ref)
            assertArrayEquals(if (index == 0) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6), saved.bytes)
            assertEquals(if (index == 0) "image/png" else "image/jpeg", saved.mime)
        }
        val compact = stock.requests.single { it.url.encodedPath == "/api/sessions/stored-a/messages" }
        assertEquals("false", compact.url.queryParameter("inline_images"))
        assertEquals("500", compact.url.queryParameter("limit"))
        val opened = stock.requests.filter { it.url.encodedPath.endsWith("/around") }
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.url.encodedPath == "/api/sessions/stored-a/messages/around" && it.url.queryParameter("row_id") == "77" && it.url.queryParameter("limit") == "1" })
        assertTrue("The mutable path never replaces either original", stock.requests.none { it.url.encodedPath in setOf("/api/fs/download", "/api/file/raw", "/api/media") })
        assertProfiled(stock)
    }

    @Test fun pathOnlyPhotosKeepNativeFileReferencesAndMixedUnmatchedPathsStayAvailable() = runBlocking {
        val stock = Stock().apply {
            messages = listOf(
                json("id" to 76, "role" to "user", "content" to "@image:/workspace/path-only.png"),
                json("id" to 77, "role" to "user", "content" to JSONArray()
                    .put(json("type" to "text", "text" to "@image:/workspace/first.png\n@image:/workspace/second.png"))
                    .put(json("type" to "image_url", "image_url" to json("url" to "data:image/png;base64,AQID")))))
        }
        val c = client(stock)
        val messages = c.get("/api/session", mapOf("session_id" to "stored-a")).getJSONArray("messages")
        val path = resolveAttachmentReference(messages.getJSONObject(0).getJSONArray("attachments").getJSONObject(0), c.scope.session("stored-a")) as NativeReference.LocalMedia
        assertEquals("/workspace/path-only.png", path.path)
        val mixed = messages.getJSONObject(1).getJSONArray("attachments")
        val refs = (0 until mixed.length()).map { resolveAttachmentReference(mixed.getJSONObject(it), c.scope.session("stored-a")) }
        assertEquals(2, refs.filterIsInstance<NativeReference.LocalMedia>().size)
        assertEquals(listOf(NativeReference.HistoryImage("stored-a", 77, 0)), refs.filterIsInstance<NativeReference.HistoryImage>())
    }

    @Test fun historyCarriesTheRawDisplayedBaselineEvenWhenANewerRowArrivesBeforeWatching() = runBlocking {
        val stock = Stock(); val c = client(stock)
        val initial = c.get("/api/session", mapOf("session_id" to "stored-a"))
        val baseline = displayedNativeHistoryStamp(initial)
        assertNotNull(baseline)
        stock.messages = stock.messages + json("id" to 99, "role" to "assistant", "content" to "Landed before watcher startup")
        val unread = c.nativeRequest("/api/sessions/stored-a", params = mapOf("profile" to "work"))
        assertTrue(nativeHistoryNeedsRefresh(baseline, nativeHistoryStamp(unread)))
        val reconciled = c.get("/api/session", mapOf("session_id" to "stored-a"))
        assertEquals("Landed before watcher startup", reconciled.getJSONArray("messages").getJSONObject(3).getString("content"))
        assertFalse(nativeHistoryNeedsRefresh(displayedNativeHistoryStamp(reconciled), nativeHistoryStamp(unread)))
    }

    @Test fun compressedInlineOnlyImageUsesABoundedFallbackAndRejectsAShiftedRow() = runBlocking {
        val stock = Stock().apply {
            compressedTip = "stored-a-tip"
            messages = listOf(json("id" to 7, "role" to "user", "content" to JSONArray()
                .put(json("type" to "text", "text" to "Ancestor photo"))
                .put(json("type" to "image_url", "image_url" to json("url" to "data:image/png;base64,AQID")))))
            override = { request -> if (request.url.encodedPath.endsWith("/around")) 404 to json("detail" to "Prompt not found").toString() else null }
        }
        val c = client(stock)
        assertArrayEquals(byteArrayOf(1, 2, 3), c.historyImage(NativeReference.HistoryImage("stored-a", 7, 0)).bytes)
        val raw = stock.requests.single { it.url.queryParameter("inline_images") == "true" }
        assertEquals("1", raw.url.queryParameter("limit")); assertEquals("0", raw.url.queryParameter("offset"))
        stock.override = { request -> when {
            request.url.encodedPath.endsWith("/around") -> 404 to json("detail" to "Prompt not found").toString()
            request.url.queryParameter("inline_images") == "true" -> 200 to json("profile" to "work", "session_id" to "stored-a-tip", "messages" to JSONArray().put(json("id" to 8, "role" to "user", "content" to JSONArray()))).toString()
            else -> null
        } }
        assertTrue("Never preview a neighbouring row after an offset race", runCatching { c.historyImage(NativeReference.HistoryImage("stored-a", 7, 0)) }.isFailure)
    }

    @Test fun exactLazyHistoryRouteRejectsOtherIdentitiesAndPolicyExpansions() = runBlocking {
        assertTrue(NativeOperationPolicy.direct("/api/sessions/stored-a/messages/around", "GET"))
        for (path in listOf("/api/sessions/../messages/around", "/api/sessions/stored-a/messages/around/other", "/api/sessions/stored-a/messages/evil"))
            assertFalse(path, NativeOperationPolicy.direct(path, "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/sessions/stored-a/messages/around", "POST"))
        for (field in listOf("profile", "session_id")) {
            val stock = Stock().apply { override = { request -> if (request.url.encodedPath.endsWith("/around"))
                200 to json("profile" to "work", "session_id" to "stored-a", "messages" to JSONArray().put(json("id" to 77, "role" to "user"))).put(field, "foreign").toString() else null } }
            assertTrue(runCatching { client(stock).historyImage(NativeReference.HistoryImage("stored-a", 77, 0)) }.isFailure)
        }
        val wrongRow = Stock().apply { override = { request -> if (request.url.encodedPath.endsWith("/around"))
            200 to json("profile" to "work", "session_id" to "stored-a", "messages" to JSONArray().put(json("id" to 78, "role" to "user"))).toString() else null } }
        assertTrue(runCatching { client(wrongRow).historyImage(NativeReference.HistoryImage("stored-a", 77, 0)) }.isFailure)
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
