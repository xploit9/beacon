package dev.hermes.android

import android.net.Uri
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
internal suspend fun loginLiveTest(app: CypherApplication, origin: String, credentials: JSONObject) {
    CypherClient(AuthContext(origin, emptyList()), app.auth).login(credentials.getString("password"), username = credentials.getString("username"))
}

/** Synthetic in-process native protocol fixture. No socket, service, model, mailbox or sensor. */
internal class NativeExperienceFixture : WebSocket.Factory {
    val requests = CopyOnWriteArrayList<JSONObject>()
    val sessions = ConcurrentHashMap<String, JSONObject>()
    private val unpromptedShells = ConcurrentHashMap<String, JSONObject>()
    val jobs = ConcurrentHashMap<String, JSONObject>()
    val boardTasks = ConcurrentHashMap<String, JSONObject>()
    val projects = ConcurrentHashMap<String, JSONObject>()
    val closedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile var skillEnabled = true
    @Volatile var skillText = "---\nname: travel-planning\ndescription: Plan a trip\n---\nRead verified sources."
    @Volatile var rejectSkillSave = false
    @Volatile var rejectMemorySave = false
    val memoryNotes = ConcurrentHashMap(mapOf("memory" to "Prefers quiet places", "user" to "Synthetic owner"))
    @Volatile var toolEnabled = true
    @Volatile var connectionEnabled = true
    @Volatile var profileModel = "synthetic-model"
    @Volatile var reasoningEffort = "medium"
    @Volatile var updateApplied = false
    @Volatile var voiceProvider = "gemini"
    val voices = ConcurrentHashMap(mapOf("gemini" to "Kore", "grok" to "ara"))
    val personalities = ConcurrentHashMap(mapOf("gemini" to "balanced", "grok" to "balanced"))
    @Volatile var roomName = "Shared workspace"
    @Volatile var botProfiles = listOf("default", "coder")
    @Volatile var roomMembers = listOf("default", "coder")
    @Volatile var roomDeleted = false
    @Volatile var rejectRoomSend = false
    val roomEvents = CopyOnWriteArrayList<JSONObject>()
    val roomActions = CopyOnWriteArrayList<JSONObject>()
    val workspaceFiles = ConcurrentHashMap<String, String>()
    val downloads = ConcurrentHashMap<String, ByteArray>()
    val fileRequests = CopyOnWriteArrayList<String>()
    val holdNextFileList = java.util.concurrent.atomic.AtomicBoolean(false)
    val fileListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseFileList = java.util.concurrent.CountDownLatch(1)
    val holdNextToolOperation = java.util.concurrent.atomic.AtomicBoolean(false)
    val toolOperationCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseToolOperation = java.util.concurrent.CountDownLatch(1)
    val holdNextRoomRead = java.util.concurrent.atomic.AtomicBoolean(false)
    val roomReadCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseRoomRead = java.util.concurrent.CountDownLatch(1)
    val holdNextSessionList = java.util.concurrent.atomic.AtomicBoolean(false)
    val sessionListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseSessionList = java.util.concurrent.CountDownLatch(1)
    @Volatile var approvalMode = "manual"
    @Volatile var rejectApprovalWrite = false
    /** Hermes's lazy session.resume can omit info.yolo even after a session-scoped yolo write. */
    @Volatile var omitResumeYolo = false
    @Volatile var rejectPromptResponse = false
    @Volatile var onPromptResponse: (() -> Unit)? = null
    @Volatile var commandCatalogFailure = false
    @Volatile var commandDistractors = 0
    private val sequence = AtomicInteger(1)
    private val notificationSequence = AtomicInteger()
    @Volatile var notificationProfiles = listOf("default", "coder")
    @Volatile var incomingNotification: JSONObject? = null
    val notificationWaits = CopyOnWriteArrayList<Long>()
    fun markUnpromptedShell(id: String) {
        unpromptedShells[id] = sessions.remove(id) ?: error("Synthetic session missing")
    }
    fun forgetUnpromptedShell(id: String) {
        check(unpromptedShells.remove(id) != null) { "Synthetic unprompted shell missing" }
    }
    fun emitIncomingCall(profile: String = "default", conversationId: String = "chat-a", expiresInSeconds: Long = 120): JSONObject {
        require(profile in notificationProfiles)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val sequence = notificationSequence.incrementAndGet()
        val source = "source-$profile-${id.take(12)}"
        val event = json(
            "seq" to sequence,
            "event_id" to id,
            "callback_id" to id,
            "owner_id" to scope.owner,
            "profile" to profile,
            "kind" to "incoming_call",
            "state" to "pending",
            "source_ref" to source,
            "conversation_ref" to "conversation-$profile-${id.take(12)}-$conversationId",
            "conversation_id" to conversationId,
            "session_id" to conversationId,
            "expires_at" to (System.currentTimeMillis() / 1000 + expiresInSeconds),
        )
        incomingNotification = event
        return event
    }
    @Volatile var cronFailure: String? = null
    @Volatile var historyFailure = false
    /** When set, every optional `/api/plugins/cypher/` route answers with this status. */
    @Volatile var pluginStatus: Int? = null
    @Volatile var boardFailure = false
    @Volatile var preferredBoard = "default"
    @Volatile var fileText = "# Travel notes\nCheck the weather."
    @Volatile var fileWrites = 0
    @Volatile var holdCreateAcknowledgement = false
    private val delayedCreates = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var activeNativeListener: WebSocketListener? = null
    @Volatile private var activeNativeSocket: WebSocket? = null
    @Volatile private var activeNativeSessionId: String? = null
    private val pendingNativeStreams = ConcurrentHashMap.newKeySet<String>()
    private val runtimeStoredSessionIds = ConcurrentHashMap<String, String>()
    private val currentRuntimeSessionIds = ConcurrentHashMap<String, String>()
    val streamSubscriptions = AtomicInteger()
    @Volatile var nativeTurnStatus = "idle"
    @Volatile var resumeRuntimeSessionId: String? = null
    @Volatile var nativeStatusFailure = false
    @Volatile var persistTerminalMessages = false
    @Volatile var terminalOnActiveList: JSONObject? = null
    @Volatile var customTabLaunches = 0
    @Volatile var callbackFailure: Throwable? = null
    private val nativeEvents = CopyOnWriteArrayList<JSONObject>()
    private val nativeEventSequence = AtomicInteger()
    fun releaseCreateAcknowledgement() {
        val queued = synchronized(delayedCreates) { holdCreateAcknowledgement = false; delayedCreates.toList().also { delayedCreates.clear() } }
        queued.forEach { it() }
    }
    fun installCustomTabSeam() {
        callbackFailure = null
        NativeAuth.customTabLauncher = { _, authorizeUrl ->
            customTabLaunches += 1
            val state = authorizeUrl.getQueryParameter("state").orEmpty()
            val redirect = Uri.parse(authorizeUrl.getQueryParameter("redirect_uri") ?: error("Missing loopback redirect"))
                .buildUpon().appendQueryParameter("code", "synthetic-code").appendQueryParameter("state", state).build()
            Thread {
                runCatching {
                    client.http.newCall(Request.Builder().url(authorizeUrl.toString()).get().build()).execute().close()
                    val port = redirect.port
                    java.net.Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 5_000
                        val path = redirect.encodedPath + "?" + redirect.encodedQuery
                        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                        socket.getInputStream().bufferedReader().use { reader -> while (reader.readLine() != null) { } }
                    }
                }.onFailure { callbackFailure = it }
            }.apply { name = "cypher-oauth-test-callback"; isDaemon = true; start() }
        }
    }
    fun clearCustomTabSeam() { NativeAuth.customTabLauncher = null }
    fun retainNativeEvent(runtimeSessionId: String, type: String, payload: JSONObject): JSONObject {
        val storedSessionId = runtimeStoredSessionIds[runtimeSessionId] ?: sessions.entries.firstOrNull { it.value.optString("session_id") == runtimeSessionId }?.key
            ?: error("Synthetic runtime session missing: $runtimeSessionId")
        val event = json("session_id" to runtimeSessionId, "seq" to nativeEventSequence.incrementAndGet(), "type" to type, "payload" to payload.copy())
        if (persistTerminalMessages && type == "message.complete") {
            sessions[storedSessionId]?.optJSONArray("messages")?.put(json("role" to "assistant", "text" to payload.string("text"), "row_id" to "terminal-${event.optInt("seq")}"))
        }
        nativeEvents.add(event)
        return event
    }
    fun awaitActiveNativeSocket(timeoutMs: Long = 15_000L): Pair<WebSocketListener, WebSocket> {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val listener = activeNativeListener
            val socket = activeNativeSocket
            if (listener != null && socket != null) return listener to socket
            if (System.nanoTime() >= deadline) error("No active native socket for ${activeNativeSessionId ?: "the pending conversation"} after ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
    fun activeRuntimeForTest(): String? = activeNativeSessionId
    fun emitNativeEvent(type: String, payload: JSONObject) {
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val event = retainNativeEvent(runtime, type, payload)
        val (listener, socket) = awaitActiveNativeSocket(0)
        listener.onMessage(socket, json("method" to "event", "params" to event).toString())
    }
    fun interruptActiveNativeStream() {
        val (listener, socket) = awaitActiveNativeSocket(0)
        activeNativeListener = null
        activeNativeSocket = null
        activeNativeSessionId = null
        listener.onFailure(socket, java.io.IOException("Synthetic native WebSocket interruption"), null)
    }
    val scope = Scope("https://ui-fixture.invalid", "synthetic-owner", "default")
    val client = CypherClient(AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard"), null, this) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "ui-fixture.invalid") { "UI fixture may not access a real host" }
            if (request.url.encodedPath in setOf("/api/fs/download", "/api/files/download", "/api/file/raw")) {
                val path = request.url.queryParameter("path").orEmpty()
                fileRequests += "${request.method} ${request.url.encodedPath}?path=$path"
                val bytes = downloads[path]
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (bytes == null) 404 else 200).message("Synthetic download")
                    .body((bytes ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            val body = buffer.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            val profileQuery = if (request.url.encodedPath.startsWith("/api/plugins/cypher/")) "cypher_profile" else "profile"
            val profile = request.url.queryParameter(profileQuery) ?: "default"
            requests += if (request.url.encodedPath.startsWith("/auth/native/")) json(
                "path" to request.url.encodedPath,
                "method" to request.method,
                "body_fields" to JSONArray().apply { body.keys().forEach { put(it) } },
            )
                else json("path" to request.url.encodedPath, "method" to request.method, "body" to body, "profile" to profile)
            val outage = pluginStatus?.takeIf { request.url.encodedPath.startsWith("/api/plugins/cypher/") }
            val (status, result) = if (outage != null) outage to json("detail" to "Synthetic optional plugin outage")
                else http(request.url.encodedPath, request.method, body, profile, request.url.queryParameter("path").orEmpty(), request.url.queryParameter("include_archived") == "1" || request.url.queryParameter("archived") == "only", request.url)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    fun clientFor(auth: AuthContext, authStore: AuthStore? = null) = CypherClient(
        initial = auth,
        authStore = authStore,
        socketFactory = this,
        sharedHttp = client.http,
        persistContext = false,
        configureHttp = {},
    )
    init {
        projects["p_project_a"] = json("id" to "p_project_a", "name" to "Travel", "primary_path" to "C:/synthetic-workspace", "folders" to JSONArray().put(json("path" to "C:/synthetic-workspace", "is_primary" to true)))
        sessions["chat-a"] = history("chat-a", "Travel planning", JSONArray().put(json("role" to "user", "text" to "Compare the two plans.", "row_id" to 10)).put(json("role" to "assistant", "text" to "A clear comparison follows.\n\n| Plan | Duration | Status |\n|---|---:|---|\n| City | 3 days | Ready |\n| Mountains and the surrounding national park | 5 days | Needs dates |\n\n```kotlin\nval ready = true\n```", "row_id" to 11)))
        sessions["chat-b"] = history("chat-b", "Reading list", JSONArray().put(json("role" to "user", "text" to "Keep the literal <memory-context>example</memory-context> in this note.", "row_id" to 20)))
        sessions.forEach { (id, row) -> runtimeStoredSessionIds[row.string("session_id")] = id; currentRuntimeSessionIds[id] = row.string("session_id") }
        jobs["task-a"] = json("id" to "task-a", "name" to "Morning summary", "prompt" to "Summarize synthetic sample notes. No external action.", "schedule" to json("kind" to "cron", "expression" to "0 10 * * *", "display" to "Every day at 10:00"), "schedule_display" to "0 10 * * *", "deliver" to "local", "paused" to false, "enabled" to true, "last_status" to "success", "next_run_at" to "2026-10-15T10:00:00+04:00", "scheduler_heartbeat_age_s" to 8)
        boardTasks["t_fixture"] = json("id" to "t_fixture", "title" to "Review travel options", "body" to "Compare the two plans before booking.", "status" to "todo", "assignee" to "default", "priority" to 1)
    }
    private fun history(id: String, title: String, messages: JSONArray) = json("session_id" to "runtime-$id", "stored_session_id" to id,
        "info" to json("title" to title, "model" to "synthetic-model", "provider" to "fixture", "cwd" to "C:/synthetic-workspace", "yolo" to false), "messages" to messages, "running" to false)
    fun snapshot(id: String) = sessions.getValue(id).copy().apply {
        val info = getJSONObject("info"); info.keys().forEach { put(it, info.opt(it)) }
        put("runtime_session_id", getString("session_id")); put("session_id", id)
    }
    /** Synthetic stock dashboard history: metadata and the newest message window. */
    private fun stockSession(path: String, profile: String): Pair<Int, JSONObject> {
        val parts = path.removePrefix("/api/sessions/").split('/')
        val id = parts.first()
        val row = sessions[id]
        if (historyFailure || row == null) return 404 to json("detail" to "Session not found")
        val messages = row.getJSONArray("messages")
        return when (parts.getOrNull(1)) {
            null -> 200 to row.getJSONObject("info").copy().put("id", id).put("profile", profile).put("message_count", messages.length())
            "messages" -> {
                val window = JSONArray((maxOf(0, messages.length() - 500) until messages.length()).map(messages::get))
                200 to json("session_id" to id, "profile" to profile, "messages" to window, "pagination" to json("limit" to 500, "offset" to 0, "order" to "latest", "returned" to window.length()))
            }
            "latest-descendant" -> 200 to json("requested_session_id" to id, "session_id" to id, "path" to JSONArray().put(id), "changed" to false)
            else -> 404 to json("detail" to "Not Found")
        }
    }
    private fun http(path: String, method: String, body: JSONObject, profile: String, directory: String, archived: Boolean = false, url: HttpUrl? = null): Pair<Int, JSONObject> {
        if (path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room" && holdNextRoomRead.getAndSet(false)) { roomReadCaptured.countDown(); check(releaseRoomRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        if (path == "/api/sessions" && holdNextSessionList.getAndSet(false)) { sessionListCaptured.countDown(); check(releaseSessionList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        return when {
        path == "/api/profiles" -> 200 to json("profiles" to JSONArray(notificationProfiles.map { json("name" to it) }))
        path == "/api/profiles/active" -> 200 to json("current" to profile, "active" to profile)
        path == "/api/plugins/cypher/mobile/notifications/events" -> {
            val after = url?.queryParameter("after")?.toLongOrNull() ?: 0L
            val wait = url?.queryParameter("wait")?.toLongOrNull() ?: 0L
            notificationWaits += wait
            var event = incomingNotification?.takeIf { it.optLong("seq") > after && it.string("profile") == profile }
            if (event == null && wait > 0) {
                Thread.sleep(minOf(wait * 1000, 50))
                event = incomingNotification?.takeIf { it.optLong("seq") > after && it.string("profile") == profile }
            }
            200 to json("events" to JSONArray().apply { event?.let { put(JSONObject(it.toString())) } }, "next_cursor" to (event?.optLong("seq") ?: after))
        }
        path.startsWith("/api/plugins/cypher/mobile/notifications/events/") -> {
            val id = path.substringAfterLast('/')
            val event = incomingNotification?.takeIf { it.string("event_id") == id && it.string("profile") == profile }
            if (event == null) 404 to json("detail" to "Notification missing") else 200 to JSONObject(event.toString())
        }
        path.startsWith("/api/plugins/cypher/mobile/callbacks/") -> {
            val rest = path.removePrefix("/api/plugins/cypher/mobile/callbacks/")
            val id = rest.substringBefore('/')
            val action = rest.substringAfterLast('/')
            val event = incomingNotification?.takeIf { it.string("event_id") == id && it.string("profile") == profile }
            if (event == null) 404 to json("detail" to "Invitation missing")
            else if (action == "decline" && method == "POST") {
                event.put("state", "declined")
                200 to json("declined" to true, "state" to "declined", "callback_id" to id, "kind" to "incoming_call", "profile" to profile)
            } else if (action == "answer" && method == "POST") {
                event.put("state", "answered")
                200 to json("call_id" to "fixture-call", "conversation_id" to event.string("session_id"), "profile" to profile,
                    "transport" to "gemini_live_direct", "provider" to "gemini", "callback_id" to id, "kind" to "incoming_call", "state" to "answered")
            } else 404 to json("detail" to "Unknown callback action")
        }
        path == "/api/plugins/cypher/mobile/voice-settings" -> {
            if (method == "PUT") {
                voiceProvider = body.getString("provider"); check(voices.containsKey(voiceProvider))
                if (body.has("voice")) voices[voiceProvider] = body.getString("voice")
                if (body.has("personality")) personalities[voiceProvider] = body.getString("personality")
            }
            200 to json("selected_provider" to voiceProvider, "providers" to JSONArray(listOf("gemini", "grok").map { id ->
                json("id" to id, "label" to if (id == "gemini") "Gemini Live" else "Grok Voice", "available" to true, "vision" to (id == "gemini"), "voice" to voices[id], "personality" to personalities[id],
                    "voices" to JSONArray(if (id == "gemini") listOf("Kore", "Puck") else listOf("ara", "eve")), "personalities" to JSONArray(listOf("balanced", "concise", "warm").map { json("id" to it, "label" to it.replaceFirstChar(Char::titlecase)) }))
            }))
        }
        path == "/api/plugins/cypher/mobile/bot-mode" -> 200 to json("bots" to JSONArray(botProfiles.map { json("profile" to it, "title" to it, "description" to "Synthetic bot") }), "spaces" to JSONArray().apply { if(!roomDeleted) put(json("room_id" to "fixture-room", "name" to roomName)) })
        path == "/api/plugins/cypher/mobile/bot-mode/chat" && method == "POST" -> {
            val bot = body.getString("bot"); check(bot in setOf("default", "coder"))
            val id = "bot-$bot"
            sessions.putIfAbsent(id, history(id, "Bot Chat", JSONArray()))
            200 to json("profile" to bot, "title" to "Bot Chat", "stored_session_id" to id)
        }
        path == "/api/plugins/cypher/mobile/bot-mode/space" && method == "POST" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName))
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room/messages" -> {
            if (rejectRoomSend) 503 to json("detail" to "Synthetic shared-space interruption") else {
                roomEvents += json("event_id" to body.getString("event_id"), "kind" to "message.user", "payload" to json("text" to body.getString("text")))
                200 to json("accepted" to true)
            }
        }
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room/action" -> {
            roomActions.removeAll { it.string("task_id") == body.string("task_id") && it.string("kind") == body.string("kind") }
            200 to json("accepted" to true)
        }
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room/members" -> {
            check(body.getJSONArray("expected_profiles").strings().toSet() == roomMembers.toSet())
            roomMembers = body.getJSONArray("profiles").strings()
            200 to json("room" to json("room_id" to "fixture-room", "name" to roomName, "members" to JSONArray(roomMembers.map { json("profile" to it) })))
        }
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room/delete" -> {
            check(body.getString("expected_name") == roomName); roomDeleted = true
            200 to json("tombstone" to json("room_id" to "fixture-room", "event_id" to body.getString("event_id")))
        }
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room/rename" -> { roomName = body.getString("name"); 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName)) }
        path == "/api/plugins/cypher/mobile/bot-mode/space/fixture-room" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName, "members" to JSONArray(roomMembers.map { json("profile" to it) })), "events" to JSONArray(roomEvents.toList()), "driver_status" to json("running" to true, "pending_actions" to JSONArray(roomActions.toList())))
        path == "/api/sessions" -> {
            // Stock dashboard index: profile-scoped pages of at most 100, archived=exclude|only.
            val limit = url?.queryParameter("limit")?.toIntOrNull() ?: 20
            val offset = url?.queryParameter("offset")?.toIntOrNull() ?: 0
            if (limit !in 1..100) 422 to json("detail" to "limit must be at most 100")
            else 200 to json("sessions" to JSONArray(sessions.values.filter { it.getJSONObject("info").optBoolean("hidden") == archived }
                .map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("profile", profile).put("message_count", row.getJSONArray("messages").length()) }
                .drop(offset).take(limit)))
        }
        path.startsWith("/api/sessions/") -> stockSession(path, profile)
        path == "/api/auth/ws-ticket" -> 200 to json("ticket" to "synthetic-once")
        path == "/auth/native/authorize" -> 200 to json("ready" to true)
        path == "/auth/native/token" -> if (body.string("code").isBlank() || body.string("code_verifier").isBlank()) 400 to json("detail" to "Invalid code or verifier") else 200 to json("access_token" to "synthetic-access", "refresh_token" to "synthetic-refresh", "expires_at" to 2_000_000_000L, "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner)
        path == "/auth/native/refresh" -> if (body.string("refresh_token").isBlank()) 401 to json("error" to "session_expired") else 200 to json("access_token" to "synthetic-access-refreshed", "refresh_token" to "synthetic-refresh-refreshed", "expires_at" to 2_000_000_000L, "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner)
        path == "/api/auth/me" -> 200 to json("user_id" to scope.owner)
        path == "/auth/logout" -> 200 to json("ok" to true)
        path == "/api/model/options" -> 200 to json("providers" to JSONArray().put(json("slug" to "fixture", "name" to "Configured provider", "models" to JSONArray(listOf("synthetic-model", "other-model", "confirm-model")))))
        path == "/api/plugins/cypher/admin/model-capabilities" -> 200 to json("reasoning_known" to true, "reasoning_options" to JSONArray(listOf("low", "medium", "high")))
        path == "/api/tools/toolsets" -> 200 to json("items" to JSONArray().put(json("name" to "web", "label" to "Web research", "description" to "Read public pages", "enabled" to toolEnabled, "configured" to true, "tools" to JSONArray().put("web_search"))))
        path == "/api/tools/toolsets/web" && method == "PUT" -> {
            if (holdNextToolOperation.compareAndSet(true, false)) { toolOperationCaptured.countDown(); check(releaseToolOperation.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            toolEnabled = body.getBoolean("enabled"); 200 to json("ok" to true)
        }
        path == "/api/mcp/servers" -> 200 to json("servers" to JSONArray().put(json("name" to "fixture_mcp", "enabled" to connectionEnabled, "transport" to "stdio", "source" to "config")))
        path == "/api/mcp/servers/fixture_mcp/enabled" -> { connectionEnabled = body.getBoolean("enabled"); 200 to json("ok" to true) }
        path == "/api/mcp/servers/fixture_mcp/test" -> 200 to json("ok" to true, "tools" to JSONArray().put(json("name" to "read_fixture")))
        path == "/api/plugins/cypher/mobile/skills" -> 200 to json("items" to JSONArray().put(json("name" to "travel-planning", "description" to "Plan a trip from verified information", "enabled" to skillEnabled, "essential" to false, "requirements" to JSONArray(), "category" to "travel", "provenance" to "local")))
        path == "/api/skills/toggle" -> { skillEnabled = body.getBoolean("enabled"); 200 to json("ok" to true, "name" to "travel-planning", "enabled" to skillEnabled) }
        path == "/api/plugins/cypher/mobile/skills/content" -> {
            if (method == "PUT") {
                if (rejectSkillSave) return 409 to json("detail" to "Instructions changed. Your draft is retained.")
                skillText = body.getString("content")
                200 to json("success" to true)
            } else 200 to json("name" to "travel-planning", "content" to skillText, "revision" to "a".repeat(64))
        }
        path == "/api/memory" -> 200 to json("active" to "builtin", "providers" to JSONArray())
        path == "/api/plugins/cypher/memory/documents" -> {
            fun document(target: String) = json("target" to target, "content" to memoryNotes.getValue(target), "revision" to "a".repeat(64), "enabled" to true, "limit" to 8000)
            if (method == "PUT") {
                if (rejectMemorySave) return 409 to json("detail" to "Notes changed. Review before saving.")
                val target = body.getString("target"); check(memoryNotes.containsKey(target)); memoryNotes[target] = body.getString("content")
                200 to json("ok" to true, "document" to document(target))
            } else 200 to json("documents" to JSONArray(listOf("memory", "user").map(::document)))
        }
        path == "/api/plugins/cypher/admin/bots" -> 200 to json("bots" to JSONArray().put(json("profile" to "default", "title" to "Personal", "purpose" to "Synthetic assistant")))
        path == "/api/plugins/cypher/admin/bot" -> {
            if (method == "PUT") profileModel = body.getString("model")
            val bot = json("profile" to profile, "title" to "Personal", "description" to "Synthetic assistant", "soul" to "Check sources before answering.", "provider" to "fixture", "model" to profileModel, "revision" to "a".repeat(64))
            200 to if (method == "PUT") json("ok" to true, "bot" to bot) else bot
        }
        path == "/api/status" -> 200 to json("version" to "fixture", "overall" to "ok", "gateway_state" to "connected", "active_agents" to 0, "components" to json("gateway" to json("status" to "ok", "state" to "connected")))
        path == "/api/logs" -> 200 to json("lines" to JSONArray())
        path == "/api/ops/checkpoints" -> 200 to json("total_bytes" to 0, "sessions" to JSONArray())
        path == "/api/hermes/update/check" -> 200 to json("current_version" to "fixture", "can_apply" to true, "update_available" to !updateApplied)
        path == "/api/plugins/cypher/admin/update/status" -> 200 to json("server_now" to "2026-10-16T00:00:00Z", "action" to if (updateApplied) json("action_id" to "fixture-update", "running" to false, "receipt" to json("started_at" to "2026-10-16T00:00:00Z", "finished_at" to "2026-10-16T00:00:01Z", "outcome" to "success")) else JSONObject.NULL)
        path == "/api/plugins/cypher/admin/update" -> { updateApplied = true; 200 to json("accepted" to true, "action_id" to "fixture-update", "requested_at" to "2026-10-16T00:00:00Z") }
        path == "/api/plugins/cypher/mobile/projects" && method == "POST" -> {
            val slug = "phone-" + body.getString("request_id")
            val existing = projects.values.firstOrNull { it.string("slug") == slug }
            if (existing != null) {
                if (existing.string("name") != body.getString("name")) 409 to json("detail" to "Already created. Refresh Projects.") else 200 to json("project" to existing)
            } else {
                val key = "p_new_${sequence.incrementAndGet()}"
                val row = json("id" to key, "name" to body.getString("name"), "description" to body.string("description"), "slug" to slug, "folders" to JSONArray())
                projects[key] = row; 200 to json("project" to row)
            }
        }
        path == "/api/files" -> {
            val current = directory.ifBlank { "C:/synthetic-workspace" }
            val rows = JSONArray().put(json("name" to "notes.md", "path" to "$current/notes.md", "is_directory" to false, "size" to 28))
            workspaceFiles.keys.sorted().forEach { rows.put(json("name" to it, "path" to "$current/$it", "is_directory" to false)) }
            rows.put(json("name" to "research", "path" to "$current/research", "is_directory" to true))
            if (holdNextFileList.getAndSet(false)) { fileListCaptured.countDown(); check(releaseFileList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            200 to json("entries" to rows, "path" to current, "parent" to "C:/", "can_change_path" to true)
        }
        path == "/api/plugins/cypher/mobile/files/text" -> {
            if (method == "POST") {
                if (body.getString("expected_content") != fileText) return 409 to json("detail" to "File changed. Reopen and review your draft.")
                fileWrites++; fileText = body.getString("content")
            }
            200 to json("content" to fileText, "text" to fileText, "binary" to false, "truncated" to false, "ok" to true)
        }
        path == "/api/fs/list" -> {
            val rows = JSONArray().put(json("name" to "notes.md", "path" to "notes.md", "type" to "file", "size" to 28))
            workspaceFiles.keys.sorted().forEach { rows.put(json("name" to it, "path" to it, "type" to "file")) }
            if (holdNextFileList.getAndSet(false)) { fileListCaptured.countDown(); check(releaseFileList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            200 to json("entries" to rows)
        }
        path == "/api/plugins/cypher/mobile/files/create" && method == "POST" -> {
            val filename = body.getString("path").replace('\\', '/').substringAfterLast('/')
            if (workspaceFiles.containsKey(filename)) 409 to json("detail" to "File already exists") else {
                val content = body.string("content"); workspaceFiles[filename] = content
                downloads[body.getString("path")] = content.toByteArray(); 200 to json("ok" to true)
            }
        }
        path == "/api/files/upload" && method == "POST" -> {
            workspaceFiles[body.getString("path").replace('\\', '/').substringAfterLast('/')] = body.getString("data_url")
            200 to json("ok" to true)
        }
        path == "/api/fs/read-text" -> 200 to json("text" to fileText, "binary" to false, "truncated" to false, "editable" to true)
        path == "/api/fs/write-text" && method == "POST" -> { check(body.getString("path").replace('\\','/').endsWith("/notes.md")) { "Unexpected fixture file write" }; fileWrites++; fileText = body.getString("content"); 200 to json("ok" to true) }
        path == "/api/plugins/kanban/boards" -> if (boardFailure) 503 to json("detail" to "Boards are offline") else 200 to json("boards" to JSONArray().put(json("slug" to "default", "name" to "Personal")).put(json("slug" to "work", "name" to "Work")), "current" to preferredBoard)
        path == "/api/plugins/kanban/board" -> 200 to json("columns" to JSONArray(dev.hermes.android.data.boardStatuses.keys.map { status -> json("name" to status, "tasks" to JSONArray(boardTasks.values.filter { it.string("status") == status })) }))
        path == "/api/plugins/kanban/tasks" && method == "POST" -> {
            val id = "t_new"; val row = body.copy().put("id", id).put("status", "todo").put("priority", 0); boardTasks[id] = row; 200 to json("task" to row)
        }
        path.startsWith("/api/plugins/kanban/tasks/") -> {
            val id = path.substringAfterLast('/'); val row = boardTasks[id] ?: return 404 to json("detail" to "Task missing")
            when (method) { "PATCH" -> { body.keys().forEach { row.put(it, body.opt(it)) }; 200 to json("task" to row) }; "DELETE" -> { boardTasks.remove(id); 200 to json("deleted" to true) }; else -> 200 to json("task" to row) }
        }
        path == "/api/cron/delivery-targets" -> 200 to json("targets" to JSONArray().put(json("id" to "local", "name" to "Local", "home_target_set" to true)).put(json("id" to "telegram", "name" to "Telegram", "home_target_set" to false)))
        path == "/api/cron/jobs" && method == "POST" -> {
            val id = "task-new"; jobs[id] = body.copy().put("id", id).put("paused", false); 200 to json("ok" to true, "job" to jobs.getValue(id))
        }
        path == "/api/cron/jobs" && method == "GET" -> 200 to json("items" to JSONArray(jobs.values.toList()))
        path.startsWith("/api/cron/jobs/") && path.endsWith("/runs") -> 200 to json("runs" to JSONArray().put(json("session_id" to "chat-b", "title" to "Synthetic completed run", "status" to "completed", "created_at" to "2026-10-14T10:00:00+04:00")), "limit" to 20)
        path.startsWith("/api/cron/jobs/") -> {
            val id = path.removePrefix("/api/cron/jobs/").substringBefore('/')
            val job = jobs[id] ?: return 404 to json("detail" to "Task not found")
            when {
                method == "PUT" -> {
                    cronFailure?.let { return 400 to json("detail" to it) }
                    val updates = body.getJSONObject("updates")
                    check(!updates.has("id") && !updates.has("next_run_at") && !updates.has("last_status")) { "Immutable/runtime fields leaked into a task edit" }
                    updates.keys().forEach { job.put(it, updates.opt(it)) }; 200 to json("job" to job, "ok" to true)
                }
                // Hermes cron/jobs.py pause_job/resume_job write enabled + state; the legacy flag is kept for older readers.
                path.endsWith("/pause") -> { job.put("paused", true).put("enabled", false).put("state", "paused"); 200 to json("job" to job, "ok" to true) }
                path.endsWith("/resume") -> { job.put("paused", false).put("enabled", true).put("state", "scheduled"); 200 to json("job" to job, "ok" to true) }
                path.endsWith("/trigger") -> 200 to json("ok" to true, "status" to "queued")
                method == "DELETE" -> { jobs.remove(id); 200 to json("ok" to true) }
                else -> 200 to json("job" to job)
            }
        }
        path.startsWith("/api/plugins/cypher/mobile/sessions/") && path.endsWith("/reserve") -> 200 to json("reserved" to true, "stored_session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to profile)
        path.endsWith("/voice-history") -> 200 to json("session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to scope.profile, "events" to JSONArray())
        path.endsWith("/notifications/status") -> 200 to json("android_configured" to false, "targets" to JSONArray(), "delivery" to json("pending" to 0), "available" to false)
        path.endsWith("/tasks/capabilities") -> 200 to json("timezone" to "Asia/Dubai", "random_checkups" to false)
        path == "/api/plugins/cypher/mobile/bootstrap" -> 200 to json("contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to scope.owner, "profile" to profile, "voice" to json("available" to false))
        else -> 404 to json("detail" to "Synthetic fixture has no response for $path")
    } }
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        check(request.url.host == "ui-fixture.invalid")
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
            override fun send(text: String): Boolean {
                val frame = JSONObject(text); requests += frame.copy()
                try {
                    val method = frame.getString("method")
                    val params = frame.optJSONObject("params") ?: JSONObject()
                    when (method) {
                        "prompt.submit", "session.steer" -> params.string("session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                        "session.active_list" -> params.string("current_session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                    }
                    val result = rpc(method, params)
                    val response = json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString()
                    val deferred = synchronized(delayedCreates) {
                        if (holdCreateAcknowledgement && method == "session.create") { delayedCreates += { listener.onMessage(this, response) }; true } else false
                    }
                    if (!deferred) listener.onMessage(this, response)
                    if (!deferred && method == "session.events.since") {
                        val runtimeSessionId = params.string("session_id")
                        if (pendingNativeStreams.remove(runtimeSessionId)) {
                            activeNativeListener = listener
                            activeNativeSocket = this
                            activeNativeSessionId = runtimeSessionId
                            streamSubscriptions.incrementAndGet()
                        }
                    }
                } catch (error: IllegalArgumentException) {
                    val missing = error.message == "Synthetic session missing"
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to if (missing) 4007 else 404, "message" to if (missing) "session not found" else error.message)).toString())
                }
                return true
            }
        }
        listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic wire").build())
        return socket
    }
    private fun rpc(method: String, params: JSONObject): JSONObject {
        check(params.string("profile") in setOf("default", "coder"))
        val id = params.string("session_id").let { runtimeStoredSessionIds[it] ?: it.removePrefix("runtime-") }
        return when (method) {
            "session.list" -> json("sessions" to JSONArray(sessions.values.filter { params.optBoolean("include_hidden") || !it.getJSONObject("info").optBoolean("hidden") }.map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("stored_session_id", row.string("stored_session_id")) }))
            "projects.list" -> json("projects" to JSONArray(projects.values.toList()))
            "projects.for_cwd" -> json("project" to projects.values.filter { project -> project.rows("folders").any { params.getString("cwd").startsWith(it.string("path")) } }.maxByOrNull { project -> project.rows("folders").maxOfOrNull { it.string("path").length } ?: 0 })
            "projects.get" -> json("project" to projects.getValue(params.getString("id")))
            "projects.create" -> {
                val key = "p_new_${sequence.incrementAndGet()}"
                val row = json("id" to key, "name" to params.getString("name"), "description" to params.string("description"), "slug" to params.string("slug"), "folders" to JSONArray())
                projects[key] = row; json("project" to row)
            }
            "projects.update" -> { val row = projects.getValue(params.getString("id")); row.put("name", params.getString("name")); json("project" to row) }
            "projects.delete" -> { projects.remove(params.getString("id")); json("projects" to JSONArray(projects.values.toList())) }
            "projects.add_folder" -> {
                val row = projects.getValue(params.getString("id")); val folders = row.getJSONArray("folders"); val path = params.getString("path")
                if (folders.length() == 0) row.put("primary_path", path)
                folders.put(json("path" to path, "is_primary" to (folders.length() == 0))); json("project" to row)
            }
            "session.cwd.set" -> { sessions.getValue(id).getJSONObject("info").put("cwd", params.getString("cwd")); json("cwd" to params.getString("cwd")) }
            "projects.tree" -> json("projects" to JSONArray().put(json("id" to "p_project_a", "label" to "Travel", "path" to "C:/synthetic-workspace", "sessionCount" to 2)), "scoped_session_ids" to JSONArray().put("chat-a").put("chat-b"))
            "projects.project_sessions" -> json("project" to json("id" to params.getString("project_id"), "label" to "Travel", "sessionIds" to JSONArray().put("chat-a").put("chat-b"), "previewSessions" to JSONArray().put(json("id" to "chat-a", "title" to "Travel planning", "profile" to scope.profile))))
            "session.resume" -> {
                require(!historyFailure) { "Synthetic history unavailable" }
                val row = sessions[id] ?: unpromptedShells.remove(id)?.also { sessions[id] = it }
                row?.copy()?.apply {
                    val runtime = resumeRuntimeSessionId ?: string("session_id")
                    put("session_id", runtime)
                    runtimeStoredSessionIds[runtime] = id
                    currentRuntimeSessionIds[id] = runtime
                    put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id); if (omitResumeYolo) getJSONObject("info").remove("yolo")
                }
                    ?: throw IllegalArgumentException("Synthetic session missing")
            }
            "session.history" -> { require(!historyFailure) { "Synthetic history unavailable" }; sessions[id]?.copy()?.apply { put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id) } ?: throw IllegalArgumentException("Synthetic session missing") }
            "session.create" -> { val key = "new-${sequence.incrementAndGet()}"; history(key, params.string("title", "New conversation"), JSONArray()).also { row ->
                runtimeStoredSessionIds[row.string("session_id")] = key
                currentRuntimeSessionIds[key] = row.string("session_id")
                sessions[key] = row
            } }
            "session.events.since" -> json("epoch" to "synthetic", "latest_seq" to nativeEventSequence.get(), "events" to JSONArray(nativeEvents.filter { it.optLong("seq") > params.optLong("last_seen", 0) }))
            "session.active_list" -> {
                if (nativeStatusFailure) throw IllegalArgumentException("Synthetic active status unavailable")
                val runtime = params.optString("current_session_id")
                terminalOnActiveList?.takeIf { runtime.isNotBlank() }?.let { payload ->
                    terminalOnActiveList = null
                    retainNativeEvent(runtime, "message.complete", payload)
                }
                val stored = runtimeStoredSessionIds[runtime] ?: runtime.takeIf { it.startsWith("runtime-") }?.removePrefix("runtime-")
                val rows = JSONArray()
                if (stored != null && sessions.containsKey(stored) && currentRuntimeSessionIds[stored]?.let { it == runtime } != false) rows.put(json("id" to runtime, "session_key" to stored, "status" to nativeTurnStatus))
                json("sessions" to rows)
            }
            "session.usage" -> json("input_tokens" to 100, "output_tokens" to 20, "context_window" to 32000)
            "session.title" -> { sessions.getValue(id).getJSONObject("info").put("title", params.getString("title")); json("ok" to true) }
            "session.set_hidden" -> { val hidden = params.getBoolean("hidden"); sessions.getValue(id).getJSONObject("info").put("hidden", hidden); json("hidden" to hidden, "session_key" to id) }
            "session.close" -> { closedSessions.add(id); json("closed" to true) }
            "session.delete" -> { require(id in closedSessions) { "cannot delete an active session" }; sessions.remove(id); json("deleted" to id) }
            "session.branch" -> { val key = "branch-${sequence.incrementAndGet()}"; val rows = sessions.getValue(id).getJSONArray("messages"); history(key, params.string("name", "Branch"), JSONArray((0 until params.optInt("count", rows.length()).coerceIn(0, rows.length())).map(rows::get))).also { sessions[key] = it } }
            "session.compress" -> json("status" to "aborted")
            "session.undo" -> { sessions.getValue(id).put("messages", JSONArray()); json("ok" to true) }
            "config.get" -> if (params.string("key") == "provider") json("model" to profileModel, "provider" to "fixture")
                else json("value" to if (params.string("key") == "approvals.mode") approvalMode else reasoningEffort)
            "config.set" -> {
                val key = params.string("key")
                val value = params.string("value")
                if (key == "model") {
                    val requested = value
                    val model = requested.substringBefore(" --provider ")
                    if (model == "confirm-model" && !params.optBoolean("confirm_expensive_model")) return json("key" to "model", "value" to model, "confirm_required" to true, "confirm_message" to "This selected model requires your confirmation.")
                    sessions.getValue(id).getJSONObject("info").put("model", model).put("provider", requested.substringAfter(" --provider ", "fixture"))
                    return json("key" to "model", "value" to model, "confirm_required" to false)
                }
                if (key == "reasoning") reasoningEffort = value
                check(value !in setOf("show", "hide"))
                if (key == "approvals.mode") {
                    require(!rejectApprovalWrite) { "Synthetic approval write refused" }
                    require(value in nativeApprovalModes)
                    approvalMode = value
                }
                if (key == "yolo") sessions.getValue(id).getJSONObject("info").put("yolo", value in setOf("1", "on", "true"))
                json("ok" to true, "key" to key, "value" to if (key == "yolo") if (value in setOf("1", "on", "true")) "1" else "0" else value)
            }
            "approval.respond", "request.answer" -> {
                if (rejectPromptResponse) { rejectPromptResponse = false; throw IllegalArgumentException("Synthetic prompt response rejected") }
                onPromptResponse?.also { onPromptResponse = null }?.invoke()
                json("ok" to true)
            }
            "approval.pending" -> json("approvals" to JSONArray())
            "commands.catalog" -> {
                require(!commandCatalogFailure) { "Synthetic command catalog unavailable" }
                json("pairs" to JSONArray().apply { repeat(commandDistractors) { put(JSONArray().put("/candidate$it").put("The help text describes another command")) } }.put(JSONArray().put("/help").put("Show available commands")).put(JSONArray().put("/model").put("Choose a model")))
            }
            "prompt.submit" -> {
                nativeTurnStatus = "working"
                val rowId = sequence.incrementAndGet()
                if (persistTerminalMessages) sessions[id]?.optJSONArray("messages")?.put(json("role" to "user", "text" to params.string("text"), "row_id" to rowId))
                json("status" to "streaming", "user_row_id" to rowId)
            }
            "session.steer" -> json("status" to "steered", "accepted" to true)
            "session.interrupt" -> json("ok" to true)
            else -> throw IllegalArgumentException("Synthetic fixture has no response for $method")
        }
    }
}
