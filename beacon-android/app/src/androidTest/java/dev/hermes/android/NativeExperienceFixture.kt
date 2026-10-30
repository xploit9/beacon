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
internal suspend fun loginLiveTest(app: BeaconApplication, origin: String, credentials: JSONObject) {
    BeaconClient(AuthContext(origin, emptyList()), app.auth).login(credentials.getString("password"), username = credentials.getString("username"))
}

/** Synthetic in-process native protocol fixture. No socket, service, model, mailbox or sensor. */
internal class NativeExperienceFixture(private val ownerName: String = "synthetic-owner") : WebSocket.Factory {
    private class FixtureRpcFailure(val code: Int, message: String) : RuntimeException(message)
    val requests = CopyOnWriteArrayList<JSONObject>()
    val sessions = ConcurrentHashMap<String, JSONObject>()
    private val unpromptedShells = ConcurrentHashMap<String, JSONObject>()
    val jobs = ConcurrentHashMap<String, JSONObject>()
    val boardTasks = ConcurrentHashMap<String, JSONObject>()
    val projects = ConcurrentHashMap<String, JSONObject>()
    @Volatile var holdNextProjectDetail = false
    val projectDetailCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseProjectDetail = java.util.concurrent.CountDownLatch(1)
    val closedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile var deleteMissingAfterClose = false
    @Volatile var skillEnabled = true
    @Volatile var skillText = "---\nname: travel-planning\ndescription: Plan a trip\n---\nRead verified sources."
    @Volatile var rejectSkillSave = false
    @Volatile var rejectMemorySave = false
    val memoryNotes = ConcurrentHashMap(mapOf("memory" to "Prefers quiet places", "user" to "Synthetic owner"))
    @Volatile var toolEnabled = true
    @Volatile var connectionEnabled = true
    @Volatile var profileModel = "synthetic-model"
    @Volatile var profileIsolationBots = false
    @Volatile var profileCatalogUnavailable = false
    val isolationModels = ConcurrentHashMap(mapOf("default" to "default-model", "coder" to "coder-model", "academic" to "academic-model"))
    val isolationSkills = ConcurrentHashMap(mapOf("default" to "Default instructions", "coder" to "Coder instructions", "academic" to "Academic instructions"))
    val isolationSkillEnabled = ConcurrentHashMap(mapOf("default" to true, "coder" to true, "academic" to false))
    val isolationMemory = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>().apply {
        for (profile in listOf("default", "coder", "academic")) put(profile, ConcurrentHashMap(mapOf("memory" to "$profile notes", "user" to "$profile owner")))
    }
    val isolationApprovals = ConcurrentHashMap(mapOf("default" to "manual", "coder" to "smart", "academic" to "manual"))
    val rejectedProfiles = ConcurrentHashMap.newKeySet<String>()
    @Volatile var holdMemoryProfile = ""
    val memoryReadCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseMemoryRead = java.util.concurrent.CountDownLatch(1)
    val memoryReadReturned = java.util.concurrent.CountDownLatch(1)
    @Volatile var reasoningEffort = "medium"
    val sessionReasoning = ConcurrentHashMap<String, String>()
    @Volatile var updateApplied = false
    @Volatile var voiceProvider = "gemini"
    val proactiveEnabled = ConcurrentHashMap(mapOf("default" to false, "coder" to false))
    val proactiveDelivery = ConcurrentHashMap(mapOf("default" to "notify", "coder" to "notify"))
    val proactiveCheckMinutes = ConcurrentHashMap(mapOf("default" to 15, "coder" to 15))
    val proactiveQuietStart = ConcurrentHashMap(mapOf("default" to "22:00", "coder" to "22:00"))
    val proactiveQuietEnd = ConcurrentHashMap(mapOf("default" to "07:00", "coder" to "07:00"))
    val proactiveHealth = ConcurrentHashMap(mapOf(
        "default" to json("state" to "ok", "checked_at" to 1792497600L, "message" to ""),
        "coder" to json("state" to "ok", "checked_at" to 1792497600L, "message" to ""),
    ))
    val proactiveRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>()
    val proactiveLearned = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    val proactiveFeedbacks = CopyOnWriteArrayList<JSONObject>()
    val voices = ConcurrentHashMap(mapOf("gemini" to "Kore", "grok" to "ara"))
    val personalities = ConcurrentHashMap(mapOf("gemini" to "balanced", "grok" to "balanced"))
    @Volatile var roomName = "Shared workspace"
    @Volatile var botProfiles = listOf("default", "coder")
    @Volatile var roomMembers = listOf("default", "coder")
    @Volatile var roomDeleted = false
    @Volatile var roomCapabilitiesAvailable = true
    private fun roomCapabilities() = if (roomCapabilitiesAvailable)
        json("rename" to true, "delete" to true, "approval" to true, "change_members" to false, "retry" to false) else json()
    @Volatile var rejectRoomSend = false
    val roomEvents = CopyOnWriteArrayList<JSONObject>()
    val roomActions = CopyOnWriteArrayList<JSONObject>()
    @Volatile var callHistoryResponse = json("calls" to JSONArray(), "next" to "")
    @Volatile var callHistoryRouteMissing = false
    @Volatile var proactiveFeedbackStatusCode = 200
    @Volatile var proactiveRouteMissing = false
    @Volatile var proactiveStatusCode = 200
    @Volatile var proactiveSaveStatusCode = 200
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
    val sessionListQueries = CopyOnWriteArrayList<JSONObject>()
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
    @Volatile var profileDisplayNames = emptyMap<String, String>()
    @Volatile var profileSessionIds = emptyMap<String, Set<String>>()
    /** Profile to (status, detail) for a failed list read, as Hermes answers it (sessions.get_sessions). */
    @Volatile var sessionListFailures = emptyMap<String, Pair<Int, String>>()
    @Volatile var incomingNotification: JSONObject? = null
    val notificationWaits = CopyOnWriteArrayList<Long>()
    @Volatile var forceExpiredAccessToken: String? = null
    @Volatile var currentRefreshToken = "synthetic-refresh"
    val refreshTokensUsed = CopyOnWriteArrayList<String>()
    val rejectedRefreshTokens = CopyOnWriteArrayList<String>()
    private val refreshSequence = AtomicInteger()
    fun markUnpromptedShell(id: String) {
        unpromptedShells[id] = sessions.remove(id) ?: error("Synthetic session missing")
    }
    fun forgetUnpromptedShell(id: String) {
        check(unpromptedShells.remove(id) != null) { "Synthetic unprompted shell missing" }
        // The phone's gateway keeps its last binding and a background read can reopen its socket, so a bound shell would resume without asking Hermes; bind another chat.
        kotlinx.coroutines.runBlocking { client.nativeSession("chat-a") }
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
    fun emitTaskResult(profile: String = "default", conversationId: String = "chat-a"): JSONObject {
        require(profile in notificationProfiles)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val sequence = notificationSequence.incrementAndGet()
        val event = json(
            "seq" to sequence,
            "event_id" to id,
            "owner_id" to scope.owner,
            "profile" to profile,
            "kind" to "task_result",
            "source_ref" to "source-$profile-${id.take(12)}",
            "conversation_ref" to "conversation-$profile-${id.take(12)}-$conversationId",
            "conversation_id" to conversationId,
            "session_id" to conversationId,
            "expires_at" to (System.currentTimeMillis() / 1000 + 300),
        )
        incomingNotification = event
        return event
    }
    @Volatile var cronFailure: String? = null
    @Volatile var checkupCapabilities: JSONObject? = null
    @Volatile var checkupCapabilitiesFailure = false
    @Volatile var checkupPostEnabled = false
    @Volatile var historyFailure = false
    @Volatile var projectLookupFailure = false
    /** Chats Hermes has no saved row for yet (an unsent chat): their REST read is a 404, as on real Hermes. */
    val unsavedRows: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** When set, every optional `/api/plugins/beacon/` route answers with this status. */
    @Volatile var pluginStatus: Int? = null
    /** `devices/link` calls and their bodies; a phone is linked by signing in, with no code. */
    val deviceLinkCount = AtomicInteger()
    val deviceLinkBodies = CopyOnWriteArrayList<JSONObject>()
    val pairedDevices = CopyOnWriteArrayList<JSONObject>()
    @Volatile var deviceLinkHadAuthorization = false
    @Volatile var bootstrapPluginStatus: String? = null
    @Volatile var boardFailure = false
    @Volatile var preferredBoard = "default"
    @Volatile var fileText = "# Travel notes\nCheck the weather."
    @Volatile var fileWrites = 0
    @Volatile var holdCreateAcknowledgement = false
    @Volatile var holdPromptAcknowledgement = false
    @Volatile var holdSteerAcknowledgement = false
    @Volatile var steerStatus = "steered"
    @Volatile var steerFailureCode: Int? = null
    @Volatile var canonicalAuthority = false
    @Volatile var omitCanonicalResumeProfile = false
    @Volatile var omitCanonicalInfoProfile = false
    @Volatile var admissionReceiptStatus = "queued"
    @Volatile var admissionReceiptOutcome = ""
    @Volatile var admissionReceiptRefOverride: String? = null
    @Volatile var admissionReceiptProfileOverride: String? = null
    @Volatile var admissionReceiptIdOverride: String? = null
    @Volatile var holdReceiptAcknowledgement = false
    @Volatile var holdActiveListAcknowledgement = false
    val queueNextCanonicalSubmission = java.util.concurrent.atomic.AtomicBoolean(false)
    val canonicalAdmissions = ConcurrentHashMap<String, JSONObject>()
    @Volatile var holdConfigAcknowledgement = false
    @Volatile var holdBranchAcknowledgement = false
    @Volatile var afterNextHistoryPage: (() -> Unit)? = null
    val holdNextHistoryList = java.util.concurrent.atomic.AtomicBoolean(false)
    val historyListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseHistoryList = java.util.concurrent.CountDownLatch(1)
    /** Like Hermes when another conversation already holds the requested title: the create-time title is dropped. */
    @Volatile var dropCreateTitle = false
    @Volatile var renameFailure: String? = null
    private val delayedCreates = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var activeNativeListener: WebSocketListener? = null
    @Volatile private var activeNativeSocket: WebSocket? = null
    @Volatile private var activeNativeSessionId: String? = null
    private val serverRequestCapabilities = ConcurrentHashMap<WebSocket, Boolean>()
    private val openNativeServerRequests = ConcurrentHashMap<String, JSONObject>()
    private val nativeServerRequestSequence = AtomicInteger()
    val nativeServerResponses = CopyOnWriteArrayList<JSONObject>()
    private val pendingNativeStreams = ConcurrentHashMap.newKeySet<String>()
    private val nativeSocketSequence = AtomicInteger()
    private val replayReads = ConcurrentHashMap<WebSocket, AtomicInteger>()
    val nativeSocketMethods = CopyOnWriteArrayList<Pair<Int, String>>()
    val liveCollectorSubscriptions = AtomicInteger()
    @Volatile var liveCollectorSocketId = 0
        private set
    private val runtimeStoredSessionIds = ConcurrentHashMap<String, String>()
    private val currentRuntimeSessionIds = ConcurrentHashMap<String, String>()
    val streamSubscriptions = AtomicInteger()
    @Volatile var nativeTurnStatus = "idle"
    val nativeStatusProbes = CopyOnWriteArrayList<String>()
    @Volatile var refuseConnection = false
    val refusedConnections = java.util.concurrent.atomic.AtomicInteger()
    /** Stored id -> runtime id a later `session.resume` of that conversation binds (other conversations keep their own). */
    val resumeRuntimeSessionIds = ConcurrentHashMap<String, String>()
    /** Runtimes Hermes dropped after their socket closed: a session-scoped call naming one answers 4001, as Hermes does for a runtime not in memory. */
    val reapedRuntimes = ConcurrentHashMap.newKeySet<String>()
    /** Hermes reaps an idle detached runtime; the next `session.resume` of the stored conversation mints another. */
    fun reapRuntime(storedSessionId: String) {
        val runtime = currentRuntimeSessionIds[storedSessionId] ?: error("Synthetic runtime missing: $storedSessionId")
        reapedRuntimes += runtime
        resumeRuntimeSessionIds[storedSessionId] = "$runtime-resumed"
    }
    @Volatile var nativeStatusFailure = false
    @Volatile var persistTerminalMessages = false
    @Volatile var terminalOnActiveList: JSONObject? = null
    @Volatile var customTabLaunches = 0
    @Volatile var callbackFailure: Throwable? = null
    private val nativeEvents = CopyOnWriteArrayList<JSONObject>()
    private val nativeEventSequence = AtomicInteger()
    fun releasePromptAcknowledgement() = releaseCreateAcknowledgement()
    fun releaseCreateAcknowledgement() {
        val queued = synchronized(delayedCreates) { holdCreateAcknowledgement = false; holdPromptAcknowledgement = false; holdSteerAcknowledgement = false; holdReceiptAcknowledgement = false; holdActiveListAcknowledgement = false; holdConfigAcknowledgement = false; holdBranchAcknowledgement = false; delayedCreates.toList().also { delayedCreates.clear() } }
        queued.forEach { it() }
    }
    fun setCanonicalAdmission(id: String, status: String, outcome: String = "") {
        require(status in setOf("queued", "started", "unknown", "terminal"))
        val current = requireNotNull(canonicalAdmissions[id])
        canonicalAdmissions[id] = current.copy().put("status", status).put("outcome", outcome.ifBlank { JSONObject.NULL })
        nativeTurnStatus = when (status) { "queued" -> "starting"; "started" -> "working"; "terminal" -> "idle"; else -> "unknown" }
    }
    private fun canonicalProfileId(profile: String) = when (profile) {
        "default" -> "synthetic-authority-alpha"
        "coder" -> "synthetic-authority-beta"
        else -> "synthetic-authority-$profile"
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
            }.apply { name = "beacon-oauth-test-callback"; isDaemon = true; start() }
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
        if (type in setOf("approval", "clarify")) {
            emitNativeServerRequest(type, "fixture-$type-${nativeServerRequestSequence.incrementAndGet()}", payload)
            return
        }
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val event = retainNativeEvent(runtime, type, payload)
        val (listener, socket) = awaitActiveNativeSocket(0)
        if (type == "request.cancel") payload.opt("id")?.takeIf { it != JSONObject.NULL }?.let { openNativeServerRequests.remove(it.toString()) }
        listener.onMessage(socket, json("method" to "event", "params" to event).toString())
    }
    fun emitNativeServerRequest(method: String, id: String, payload: JSONObject) {
        require(method in setOf("approval", "clarify") && id.isNotBlank())
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val (listener, socket) = awaitActiveNativeSocket(0)
        check(serverRequestCapabilities[socket] == true) { "The fixture may emit $method only after client.capabilities server_requests=true" }
        val frame = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to payload.copy().put("session_id", runtime))
        // Hermes replays open_requests as {id, method, params} without "jsonrpc" (server_requests.py snapshot()).
        check(openNativeServerRequests.putIfAbsent(id, frame.copy().apply { remove("jsonrpc") }) == null) { "Duplicate synthetic server request id: $id" }
        listener.onMessage(socket, frame.toString())
    }
    fun retainNativeServerRequestForReplay(sessionId: String, method: String, id: String, payload: JSONObject) {
        require(sessionId.isNotBlank() && method in setOf("approval", "clarify") && id.isNotBlank())
        val frame = json("id" to id, "method" to method, "params" to payload.copy().put("session_id", sessionId))
        check(openNativeServerRequests.putIfAbsent(id, frame) == null) { "Duplicate synthetic server request id: $id" }
    }
    fun interruptActiveNativeStream() {
        val (listener, socket) = awaitActiveNativeSocket(0)
        activeNativeListener = null
        activeNativeSocket = null
        activeNativeSessionId = null
        listener.onFailure(socket, java.io.IOException("Synthetic native WebSocket interruption"), null)
    }
    val scope = Scope("https://ui-fixture.invalid", ownerName, "default", nativeProvider = "fixture")
    // Saved screen answers outlive a case; each case starts from what its own fixture serves.
    init {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<BeaconApplication>()
        kotlinx.coroutines.runBlocking { app.cache.delete(scope.copy(profile = "", session = ""), "screens") }
        // A device list without an earlier case's phone id reads as "removed on the PC"; that mark must not reach the next case.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val account = NativePairing.accountKey(scope).removePrefix("device:")
        app.getSharedPreferences("native-pairing", android.content.Context.MODE_PRIVATE).edit().remove("unlinked:$account").commit()
        app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit().remove("removed:$account").commit()
    }
    val configureHttp: (OkHttpClient.Builder) -> Unit = { builder ->
        builder.addInterceptor { chain ->
            // A network that is down refuses ordinary requests too, not only the live connection.
            if (refuseConnection) throw java.net.ConnectException("Synthetic connection refused")
            val request = chain.request()
            check(request.url.host == "ui-fixture.invalid") { "UI fixture may not access a real host" }
            if (request.url.encodedPath in setOf("/api/fs/download", "/api/files/download", "/api/file/raw")) {
                val path = request.url.queryParameter("path").orEmpty()
                fileRequests += "${request.method} ${request.url.encodedPath}?path=$path"
                val bytes = downloads[path]
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (bytes == null) 404 else 200).message("Synthetic download")
                    .body((bytes ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val body = if (request.url.encodedPath == "/api/files/upload-stream") {
                check(request.method == "POST")
                val multipart = requireNotNull(request.body as? MultipartBody)
                fun part(name: String): okio.Buffer = okio.Buffer().also { buffer ->
                    multipart.parts.single { it.headers?.get("Content-Disposition")?.substringBefore("; filename=") == "form-data; name=\"$name\"" }.body.writeTo(buffer)
                }
                check(part("overwrite").readUtf8() == "false")
                json("path" to part("path").readUtf8(), "data_url" to android.util.Base64.encodeToString(part("file").readByteArray(), android.util.Base64.NO_WRAP))
            } else {
                val buffer = okio.Buffer(); request.body?.writeTo(buffer)
                buffer.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            }
            if (request.url.encodedPath == "/api/plugins/beacon/devices/link") deviceLinkHadAuthorization = request.header("Authorization") != null
            val profileQuery = if (request.url.encodedPath.startsWith("/api/plugins/beacon/")) "beacon_profile" else "profile"
            val profile = request.url.queryParameter(profileQuery) ?: if (profileIsolationBots) body.string("profile", "default") else "default"
            requests += if (request.url.encodedPath.startsWith("/auth/native/")) json(
                "path" to request.url.encodedPath,
                "method" to request.method,
                "body_fields" to JSONArray().apply { body.keys().forEach { put(it) } },
            )
                else json("path" to request.url.encodedPath, "method" to request.method, "body" to body, "profile" to profile, "query" to request.url.encodedQuery.orEmpty())
            val expired = forceExpiredAccessToken?.let { request.header("Authorization") == "Bearer $it" } == true
            val outage = pluginStatus?.takeIf { request.url.encodedPath.startsWith("/api/plugins/beacon/") }
            val (status, result) = if (expired) 401 to json("error" to "session_expired")
                else if (outage != null) outage to json("detail" to "Synthetic optional plugin outage")
                else if (profileIsolationBots && profile in rejectedProfiles && request.url.encodedPath != "/api/profiles") 403 to json("detail" to "Synthetic profile access denied")
                else http(request.url.encodedPath, request.method, body, profile, request.url.queryParameter("path").orEmpty(), request.url.queryParameter("include_archived") == "1" || request.url.queryParameter("archived") == "only", request.url)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    val client = BeaconClient(AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider), null, this, configureHttp = configureHttp)
    fun clientFor(auth: AuthContext, authStore: AuthStore? = null) = BeaconClient(
        initial = auth,
        authStore = authStore,
        socketFactory = this,
        sharedHttp = client.http,
        persistContext = false,
        configureHttp = {},
    )
    fun freshClientFor(auth: AuthContext, authStore: AuthStore? = null) =
        BeaconClient(auth, authStore, this, persistContext = false, configureHttp = configureHttp)
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
    fun addProfileConversation(profile: String, id: String, title: String) {
        val row = history(id, title, JSONArray().put(json("role" to "user", "text" to "Synthetic conversation for $profile."))).put("profile", profile)
        sessions[id] = row
        runtimeStoredSessionIds[row.string("session_id")] = id
        currentRuntimeSessionIds[id] = row.string("session_id")
        profileSessionIds = profileSessionIds + (profile to (profileSessionIds[profile].orEmpty() + id))
    }
    fun snapshot(id: String) = sessions.getValue(id).copy().apply {
        val info = getJSONObject("info"); info.keys().forEach { put(it, info.opt(it)) }
        put("runtime_session_id", getString("session_id")); put("session_id", id)
    }
    private fun nativeDisplayText(message: JSONObject, inlineImages: Boolean): String {
        val content = message.optJSONArray("content") ?: return message.string("content", message.string("text"))
        return (0 until content.length()).joinToString("") { index -> content.getJSONObject(index).let { part ->
            if (part.string("type") == "text") part.string("text") else "\n" + if (inlineImages) part.getJSONObject("image_url").getString("url") else "[image]"
        } }
    }
    /** Synthetic stock dashboard history: metadata and the newest message window. */
    private fun stockSession(path: String, profile: String, url: HttpUrl?): Pair<Int, JSONObject> {
        val parts = path.removePrefix("/api/sessions/").split('/')
        val id = parts.first()
        val row = sessions[id]
        if (historyFailure || id in unsavedRows || row == null || profileSessionIds[profile]?.contains(id) == false) return 404 to json("detail" to "Session not found")
        val messages = row.getJSONArray("messages")
        return when (parts.getOrNull(1)) {
            null -> 200 to row.getJSONObject("info").copy().put("id", id).put("profile", profile).put("message_count", messages.length()).put("last_active", messages.length()).put("model_config", row.optString("model_config").ifBlank { "{}" })
            "messages" -> {
                val limit = url?.queryParameter("limit")?.toIntOrNull() ?: 500
                val offset = url?.queryParameter("offset")?.toIntOrNull() ?: 0
                val order = url?.queryParameter("order") ?: "latest"
                if (limit !in 1..500 || offset < 0) return 422 to json("detail" to "Invalid history page")
                val around = parts.getOrNull(2) == "around"
                val target = url?.queryParameter("row_id")?.toLongOrNull()
                val anchor = (0 until messages.length()).firstOrNull { messages.getJSONObject(it).optLong("id", messages.getJSONObject(it).optLong("row_id")) == target }
                if (around && anchor == null) return 404 to json("detail" to "Prompt not found")
                val end = if (around) anchor!! + 1 else if (order == "latest") (messages.length() - offset).coerceAtLeast(0) else (offset + limit).coerceAtMost(messages.length())
                val start = if (around) anchor!! else if (order == "latest") (end - limit).coerceAtLeast(0) else offset.coerceAtMost(end)
                val window = JSONArray((start until end).map { index ->
                    val message = messages.getJSONObject(index)
                    val content = message.optJSONArray("content")
                    if (url?.queryParameter("inline_images") != "false" || content == null) message else {
                        message.copy().put("content", nativeDisplayText(message, false))
                    }
                })
                if (!around) afterNextHistoryPage?.also { afterNextHistoryPage = null }?.invoke()
                200 to json("session_id" to id, "profile" to profile, "messages" to window, "pagination" to json("limit" to limit, "offset" to offset, "order" to order, "returned" to window.length()))
            }
            "latest-descendant" -> 200 to json("requested_session_id" to id, "session_id" to id, "path" to JSONArray().put(id), "changed" to false)
            else -> 404 to json("detail" to "Not Found")
        }
    }
    private fun proactiveResponse(profile: String) = json(
        "enabled" to (proactiveEnabled[profile] ?: false),
        "timezone" to "Asia/Dubai",
        "delivery" to (proactiveDelivery[profile] ?: "notify"),
        "check_minutes" to (proactiveCheckMinutes[profile] ?: 15),
        "quiet_start" to (proactiveQuietStart[profile] ?: "22:00"),
        "quiet_end" to (proactiveQuietEnd[profile] ?: "07:00"),
        "health" to (proactiveHealth[profile] ?: json("state" to "unknown")).copy(),
        "rules" to JSONArray(proactiveRequests[profile]?.map { it.copy() } ?: emptyList<JSONObject>()),
        "learned" to JSONArray(proactiveLearned[profile]?.toList() ?: emptyList<String>()),
        "jobs" to JSONArray(),
    )
    private fun http(path: String, method: String, body: JSONObject, profile: String, directory: String, archived: Boolean = false, url: HttpUrl? = null): Pair<Int, JSONObject> {
        if (path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room" && holdNextRoomRead.getAndSet(false)) { roomReadCaptured.countDown(); check(releaseRoomRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        if (path in setOf("/api/sessions", "/api/sessions/search") && holdNextSessionList.getAndSet(false)) { sessionListCaptured.countDown(); check(releaseSessionList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        return when {
        path == "/api/plugins/beacon/devices/link" && method == "POST" -> {
            deviceLinkCount.incrementAndGet()
            deviceLinkBodies += body.copy()
            // Like the plugin: a known id is refreshed, anything else gets a new record.
            val id = body.string("device_id").takeIf { known -> pairedDevices.any { it.string("device_id") == known } } ?: "device-123"
            val name = body.string("device_name").takeIf(String::isNotBlank) ?: "Beacon phone"
            pairedDevices.removeAll { it.string("device_id") == id }
            pairedDevices += json("device_id" to id, "name" to name, "last_seen" to "2026-10-21T00:00:00Z")
            200 to json("device_id" to id, "name" to name, "profiles" to JSONArray(listOf(scope.profile)))
        }
        path == "/api/plugins/beacon/devices" && method == "GET" -> 200 to json("devices" to JSONArray(pairedDevices.map { it.copy() }))
        path.startsWith("/api/plugins/beacon/devices/") && method == "DELETE" -> {
            val id = path.substringAfterLast('/')
            pairedDevices.removeAll { it.string("device_id") == id }
            200 to json("ok" to true)
        }
        path == "/api/profiles" -> if (profileCatalogUnavailable) 404 to json("detail" to "Synthetic catalog unavailable")
            else 200 to json("profiles" to JSONArray(notificationProfiles.map { id -> json("name" to id).apply { profileDisplayNames[id]?.let { put("display_name", it) } } }))
        path == "/api/profiles/active" -> 200 to json("current" to profile, "active" to profile)
        path == "/api/plugins/beacon/mobile/proactive" -> {
            if (proactiveRouteMissing) return 404 to json("detail" to "Unknown route")
            if (proactiveStatusCode != 200) return proactiveStatusCode to json("detail" to "Email access needs attention")
            if (method == "PUT" && proactiveSaveStatusCode != 200) return proactiveSaveStatusCode to json("detail" to "Settings change refused")
            if (method == "PUT") {
                if (body.has("enabled")) proactiveEnabled[profile] = body.getBoolean("enabled")
                if (body.has("delivery")) proactiveDelivery[profile] = body.getString("delivery")
                if (body.has("check_minutes")) proactiveCheckMinutes[profile] = body.getInt("check_minutes")
                if (body.has("quiet_start")) proactiveQuietStart[profile] = body.getString("quiet_start")
                if (body.has("quiet_end")) proactiveQuietEnd[profile] = body.getString("quiet_end")
            }
            200 to proactiveResponse(profile)
        }
        path == "/api/plugins/beacon/mobile/proactive/feedback" && method == "POST" -> {
            if (proactiveFeedbackStatusCode != 200) return proactiveFeedbackStatusCode to json("detail" to "Feedback unavailable")
            require(body.has("notice_id") && !body.has("topic") && body.getString("verdict") in setOf("important", "not_important"))
            proactiveFeedbacks += body.copy()
            200 to json("ok" to true)
        }
        path == "/api/plugins/beacon/mobile/proactive/requests" && method == "POST" -> {
            val requestText = body.getString("text")
            require(requestText.isNotBlank() && requestText.codePointCount(0, requestText.length) <= 300 && body.getString("action") in setOf("notify", "call"))
            val requests = proactiveRequests.computeIfAbsent(profile) { CopyOnWriteArrayList() }
            require(requests.size < 20)
            // R6P uses null for Anything and preserves an optional connected-source name.
            require(body.isNull("signal") || body.getString("signal").let { it.isNotBlank() && it.length <= 80 })
            val request = json("id" to "request-${java.util.UUID.randomUUID()}", "text" to body.getString("text"), "action" to body.getString("action"),
                "signal" to body.opt("signal"), "night" to body.optBoolean("night"), "enabled" to true, "created_at" to 1792400000)
            requests += request
            200 to request.copy()
        }
        path.startsWith("/api/plugins/beacon/mobile/proactive/requests/") && method in setOf("PATCH", "DELETE") -> {
            val id = path.substringAfterLast('/')
            val requests = proactiveRequests[profile]
            val request = requests?.firstOrNull { it.string("id") == id }
            if (request == null) 404 to json("detail" to "Request not found")
            else if (method == "DELETE") { requests!!.remove(request); 200 to json("ok" to true) }
            else {
                if (body.has("enabled")) request.put("enabled", body.getBoolean("enabled"))
                if (body.has("night")) request.put("night", body.getBoolean("night"))
                if (body.has("action")) { require(body.getString("action") in setOf("notify", "call")); request.put("action", body.getString("action")) }
                200 to request.copy()
            }
        }
        path == "/api/plugins/beacon/mobile/proactive/learned" && method == "DELETE" -> {
            val notes = proactiveLearned.computeIfAbsent(profile) { CopyOnWriteArrayList() }
            notes.remove(body.getString("note"))
            200 to json("ok" to true)
        }
        path == "/api/plugins/beacon/mobile/calls" -> if (callHistoryRouteMissing) 404 to json("detail" to "Unknown route") else 200 to callHistoryResponse.copy()
        path == "/api/plugins/beacon/mobile/notifications/events" -> {
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
        path.startsWith("/api/plugins/beacon/mobile/notifications/events/") -> {
            val id = path.substringAfterLast('/')
            val event = incomingNotification?.takeIf { it.string("event_id") == id && it.string("profile") == profile }
            if (event == null) 404 to json("detail" to "Notification missing") else 200 to JSONObject(event.toString())
        }
        path.startsWith("/api/plugins/beacon/mobile/callbacks/") -> {
            val rest = path.removePrefix("/api/plugins/beacon/mobile/callbacks/")
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
        path == "/api/plugins/beacon/mobile/voice-settings" -> {
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
        path == "/api/plugins/beacon/mobile/bot-mode" -> 200 to json("bots" to JSONArray(botProfiles.map { json("profile" to it, "title" to it, "description" to "Synthetic bot") }), "spaces" to JSONArray().apply { if(!roomDeleted) put(json("room_id" to "fixture-room", "name" to roomName)) }, "capabilities" to roomCapabilities())
        path == "/api/plugins/beacon/mobile/bot-mode/chat" && method == "POST" -> {
            val bot = body.getString("bot"); check(bot in setOf("default", "coder"))
            val id = "bot-$bot"
            sessions.putIfAbsent(id, history(id, "Bot Chat", JSONArray()))
            200 to json("profile" to bot, "title" to "Bot Chat", "stored_session_id" to id)
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space" && method == "POST" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName))
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/messages" -> {
            if (rejectRoomSend) 503 to json("detail" to "Synthetic shared-space interruption") else {
                roomEvents += json("event_id" to body.getString("event_id"), "kind" to "message.user", "payload" to json("text" to body.getString("text")))
                200 to json("accepted" to true)
            }
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/action" -> {
            val pending = roomActions.singleOrNull { action ->
                body.string("kind") == "approval" && action.string("kind") == "approval" &&
                    listOf("task_id", "member_id", "request_id").all { action.string(it) == body.string(it) } &&
                    action.optInt("execution_generation") == body.optInt("execution_generation") &&
                    body.string("choice") in action.getJSONObject("approval").getJSONArray("choices").strings()
            }
            if (roomCapabilitiesAvailable && pending != null) {
                roomActions.remove(pending)
                200 to json("accepted" to true)
            } else 409 to json("detail" to "Native approval was not confirmed")
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/members" -> 409 to json("detail" to "Native Hermes cannot change bots in an existing space")
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/delete" -> {
            check(body.getString("expected_name") == roomName); roomDeleted = true
            200 to json("tombstone" to json("room_id" to "fixture-room", "disbanded_at" to 1_800_000_000L,
                "idempotent" to false, "event" to json("room_id" to "fixture-room", "kind" to "room.disbanded", "event_id" to "system:room-disbanded")))
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/rename" -> { roomName = body.getString("name"); 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName)) }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName, "members" to JSONArray(roomMembers.map { json("profile" to it) })), "events" to JSONArray(roomEvents.toList()), "driver_status" to json("running" to true, "pending_actions" to JSONArray(roomActions.toList())), "capabilities" to roomCapabilities())
        path in setOf("/api/sessions", "/api/sessions/search") -> {
            // Stock dashboard index: profile-scoped pages of at most 100, archived=exclude|only.
            val limit = url?.queryParameter("limit")?.toIntOrNull() ?: 20
            val offset = url?.queryParameter("offset")?.toIntOrNull() ?: 0
            val search = if (path == "/api/sessions/search") url?.queryParameter("q").orEmpty() else ""
            // Like Hermes: `sources` keeps only those platforms, `exclude_sources` drops them.
            val only = url?.queryParameter("sources")?.split(',')?.filter(String::isNotBlank)?.toSet()
            val excluded = url?.queryParameter("exclude_sources").orEmpty().split(',').filter(String::isNotBlank).toSet()
            sessionListQueries += json("sources" to url?.queryParameter("sources"), "exclude_sources" to url?.queryParameter("exclude_sources"), "order" to url?.queryParameter("order"))
            val failure = sessionListFailures[profile]
            if (failure != null) failure.first to json("detail" to failure.second)
            else if (limit !in 1..100) 422 to json("detail" to "limit must be at most 100")
            else {
                if (holdNextHistoryList.getAndSet(false)) { historyListCaptured.countDown(); check(releaseHistoryList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                200 to json("sessions" to JSONArray(sessions.values.filter { profileSessionIds[profile]?.contains(it.string("stored_session_id")) != false &&
                    (if (archived) it.getJSONObject("info").optBoolean("archived") else !it.getJSONObject("info").optBoolean("hidden") && !it.getJSONObject("info").optBoolean("archived")) }
                .filter { search.isBlank() || it.getJSONObject("info").string("title").contains(search, ignoreCase = true) }
                .filter { row -> val source = row.getJSONObject("info").string("source"); (only == null || source in only) && source !in excluded }
                .map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("profile", profile).put("message_count", row.getJSONArray("messages").length()) }
                .drop(offset).take(limit)))
            }
        }
        path == "/api/plugins/beacon/sessions/pin" && method == "POST" -> {
            val info = sessions[body.string("session_id")]?.getJSONObject("info")
            if (info == null) 404 to json("detail" to "Conversation not found.") else {
                info.put("pinned", body.getBoolean("pinned")).put("hidden", false)
                200 to json("session_id" to body.string("session_id"), "pinned" to body.getBoolean("pinned"))
            }
        }
        // Like Hermes: a sidebar PATCH needs the revision precondition from its mutation snapshot plus a request id.
        path.startsWith("/api/sessions/") && path.endsWith("/mutation-snapshot") && method == "GET" -> path.removePrefix("/api/sessions/").removeSuffix("/mutation-snapshot").let { id ->
            val info = sessions[id]?.getJSONObject("info")
            200 to json("session_id" to id, "exists" to (info != null), "runtime_revision" to (info?.optInt("runtime_revision") ?: 0), "runtime_generation" to if (info != null) 0 else JSONObject.NULL)
        }
        // Hermes's sidebar PATCH: the only REST mutation Beacon uses (pin).
        path.startsWith("/api/sessions/") && method == "PATCH" -> path.removePrefix("/api/sessions/").let { id ->
            val info = sessions.getValue(id).getJSONObject("info")
            if (body.string("request_id").isBlank() || !body.has("expected_revision")) 409 to json("detail" to "mutation_identity_required")
            else if (body.getInt("expected_revision") != info.optInt("runtime_revision")) 409 to json("detail" to "revision_conflict")
            else { info.put("pinned", body.getBoolean("pinned")).put("runtime_revision", info.optInt("runtime_revision") + 1); 200 to json("ok" to true, "session_id" to id) }
        }
        path.startsWith("/api/sessions/") -> stockSession(path, profile, url)
        path == "/api/auth/ws-ticket" -> 200 to json("ticket" to "synthetic-once")
        path == "/auth/native/authorize" -> 200 to json("ready" to true)
        path == "/auth/native/token" -> if (body.string("code").isBlank() || body.string("code_verifier").isBlank()) 400 to json("detail" to "Invalid code or verifier") else { currentRefreshToken = "synthetic-refresh"; 200 to json("access_token" to "synthetic-access", "refresh_token" to currentRefreshToken, "expires_at" to 2_000_000_000L, "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner) }
        path == "/auth/native/refresh" -> {
            val supplied = body.string("refresh_token")
            refreshTokensUsed += supplied
            if (supplied.isBlank() || supplied != currentRefreshToken) {
                rejectedRefreshTokens += supplied
                401 to json("error" to "session_expired")
            } else {
                val number = refreshSequence.incrementAndGet()
                currentRefreshToken = "synthetic-refresh-$number"
                200 to json("access_token" to "synthetic-access-$number", "refresh_token" to currentRefreshToken, "expires_at" to (System.currentTimeMillis() / 1000 + 3600), "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner)
            }
        }
        path == "/api/auth/me" -> 200 to json("user_id" to scope.owner, "provider" to scope.nativeProvider)
        path == "/auth/logout" -> 200 to json("ok" to true)
        path == "/api/model/options" -> 200 to json("providers" to JSONArray().put(json("slug" to "fixture", "name" to "Configured provider", "models" to JSONArray(listOf("synthetic-model", "other-model", "confirm-model")))))
        path == "/api/plugins/beacon/admin/model-capabilities" -> 200 to json("reasoning_known" to true, "reasoning_options" to JSONArray(listOf("low", "medium", "high")))
        path == "/api/tools/toolsets" -> 200 to json("items" to JSONArray().put(json("name" to "web", "label" to "Web research", "description" to "Read public pages", "enabled" to toolEnabled, "configured" to true, "tools" to JSONArray().put("web_search"))))
        path == "/api/tools/toolsets/web" && method == "PUT" -> {
            if (holdNextToolOperation.compareAndSet(true, false)) { toolOperationCaptured.countDown(); check(releaseToolOperation.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            toolEnabled = body.getBoolean("enabled"); 200 to json("ok" to true)
        }
        path == "/api/mcp/servers" -> 200 to json("servers" to JSONArray().put(json("name" to "fixture_mcp", "enabled" to connectionEnabled, "transport" to "stdio", "source" to "config")))
        path == "/api/mcp/servers/fixture_mcp/enabled" -> { connectionEnabled = body.getBoolean("enabled"); 200 to json("ok" to true) }
        path == "/api/mcp/servers/fixture_mcp/test" -> 200 to json("ok" to true, "tools" to JSONArray().put(json("name" to "read_fixture")))
        path == "/api/plugins/beacon/mobile/skills" -> 200 to json("items" to JSONArray().put(json("name" to "travel-planning", "description" to "Plan a trip from verified information", "enabled" to if (profileIsolationBots) isolationSkillEnabled.getValue(profile) else skillEnabled, "essential" to false, "requirements" to JSONArray(), "category" to "travel", "provenance" to "local")))
        path == "/api/skills/toggle" -> {
            val enabled = body.getBoolean("enabled")
            if (profileIsolationBots) isolationSkillEnabled[profile] = enabled else skillEnabled = enabled
            200 to json("ok" to true, "name" to "travel-planning", "enabled" to enabled)
        }
        path == "/api/plugins/beacon/mobile/skills/content" -> {
            if (method == "PUT") {
                if (rejectSkillSave) return 409 to json("detail" to "Instructions changed. Your draft is retained.")
                if (profileIsolationBots) isolationSkills[profile] = body.getString("content") else skillText = body.getString("content")
                200 to json("success" to true)
            } else 200 to json("name" to "travel-planning", "content" to if (profileIsolationBots) isolationSkills.getValue(profile) else skillText, "revision" to "a".repeat(64))
        }
        path == "/api/memory" -> 200 to json("active" to "builtin", "providers" to JSONArray())
        path == "/api/plugins/beacon/memory/documents" -> {
            val notes = if (profileIsolationBots) isolationMemory.getValue(profile) else memoryNotes
            fun document(target: String) = json("target" to target, "content" to notes.getValue(target), "revision" to "a".repeat(64), "enabled" to true, "limit" to 8000)
            if (method == "PUT") {
                if (rejectMemorySave) return 409 to json("detail" to "Notes changed. Review before saving.")
                val target = body.getString("target"); check(notes.containsKey(target)); notes[target] = body.getString("content")
                200 to json("ok" to true, "document" to document(target))
            } else {
                if (holdMemoryProfile == profile) { holdMemoryProfile = ""; memoryReadCaptured.countDown(); check(releaseMemoryRead.await(10, java.util.concurrent.TimeUnit.SECONDS)); memoryReadReturned.countDown() }
                200 to json("documents" to JSONArray(listOf("memory", "user").map(::document)))
            }
        }
        path == "/api/plugins/beacon/admin/bots" -> 200 to json("bots" to JSONArray().put(json("profile" to "default", "title" to "Personal", "purpose" to "Synthetic assistant")).apply {
            if (profileIsolationBots) notificationProfiles.filter { it != "default" }.forEach { put(json("profile" to it, "title" to it, "purpose" to "$it assistant")) }
        })
        path == "/api/plugins/beacon/admin/bot" -> {
            if (method == "PUT") { if (profileIsolationBots) isolationModels[profile] = body.getString("model") else profileModel = body.getString("model") }
            val bot = json("profile" to profile, "title" to "Personal", "description" to "Synthetic assistant", "soul" to "Check sources before answering.", "provider" to "fixture", "model" to if (profileIsolationBots) isolationModels.getValue(profile) else profileModel, "revision" to "a".repeat(64))
            200 to if (method == "PUT") json("ok" to true, "bot" to bot) else bot
        }
        path == "/api/status" -> 200 to json("version" to "fixture", "overall" to "ok", "gateway_state" to "connected", "active_agents" to 0, "components" to json("gateway" to json("status" to "ok", "state" to "connected")))
        path == "/api/logs" -> 200 to json("lines" to JSONArray())
        path == "/api/ops/checkpoints" -> 200 to json("total_bytes" to 0, "sessions" to JSONArray())
        path == "/api/hermes/update/check" -> 200 to json("current_version" to "fixture", "can_apply" to true, "update_available" to !updateApplied)
        path == "/api/plugins/beacon/admin/update/status" -> 200 to json("server_now" to "2026-10-16T00:00:00Z", "action" to if (updateApplied) json("action_id" to "fixture-update", "running" to false, "receipt" to json("started_at" to "2026-10-16T00:00:00Z", "finished_at" to "2026-10-16T00:00:01Z", "outcome" to "success")) else JSONObject.NULL)
        path == "/api/plugins/beacon/admin/update" -> { updateApplied = true; 200 to json("accepted" to true, "action_id" to "fixture-update", "requested_at" to "2026-10-16T00:00:00Z") }
        path == "/api/plugins/beacon/mobile/projects" && method == "POST" -> {
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
            // Like Windows' My Documents: a shortcut listed under its target's path. The same path twice once crashed the list.
            rows.put(json("name" to "Research shortcut", "path" to "$current/research", "is_directory" to true))
            rows.put(json("name" to ".cache", "path" to "$current/.cache", "is_directory" to true))
            if (holdNextFileList.getAndSet(false)) { fileListCaptured.countDown(); check(releaseFileList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            200 to json("entries" to rows, "path" to current, "parent" to "C:/", "can_change_path" to true)
        }
        path == "/api/plugins/beacon/mobile/files/text" -> {
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
        path == "/api/plugins/beacon/mobile/files/create" && method == "POST" -> {
            val filename = body.getString("path").replace('\\', '/').substringAfterLast('/')
            if (workspaceFiles.containsKey(filename)) 409 to json("detail" to "File already exists") else {
                val content = body.string("content"); workspaceFiles[filename] = content
                downloads[body.getString("path")] = content.toByteArray(); 200 to json("ok" to true)
            }
        }
        path in setOf("/api/files/upload", "/api/files/upload-stream") && method == "POST" -> {
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
        path.startsWith("/api/plugins/beacon/mobile/sessions/") && path.endsWith("/reserve") -> {
            unsavedRows.remove(path.substringBeforeLast('/').substringAfterLast('/'))  // like the plugin, reserve saves the chat's row
            200 to json("reserved" to true, "stored_session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to profile)
        }
        path.endsWith("/voice-history") -> 200 to json("session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to scope.profile, "events" to JSONArray())
        path.endsWith("/notifications/status") -> 200 to json("android_configured" to false, "targets" to JSONArray(), "delivery" to json("pending" to 0), "available" to false)
        path.endsWith("/tasks/capabilities") -> if (checkupCapabilitiesFailure) 503 to json("detail" to "Synthetic capabilities unavailable")
            else 200 to (checkupCapabilities?.copy() ?: json("timezone" to "Asia/Dubai", "random_checkups" to false))
        path == "/api/plugins/beacon/mobile/tasks/checkups" && method == "POST" && checkupPostEnabled -> 200 to json("ok" to true)
        path == "/api/plugins/beacon/mobile/bootstrap" -> 200 to json("contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to scope.owner, "profile" to profile, "voice" to json("available" to false)).apply {
            bootstrapPluginStatus?.let { put("optional_features", json("plugin" to it)) }
        }
        else -> 404 to json("detail" to "Synthetic fixture has no response for $path")
    } }
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        check(request.url.host == "ui-fixture.invalid")
        val socketId = nativeSocketSequence.incrementAndGet()
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
            override fun send(text: String): Boolean {
                val frame = JSONObject(text)
                if (!frame.has("method")) {
                    if (rejectPromptResponse) { rejectPromptResponse = false; return false }
                    nativeServerResponses += frame.copy()
                    frame.opt("id")?.takeIf { it != JSONObject.NULL }?.let { openNativeServerRequests.remove(it.toString()) }
                    onPromptResponse?.also { onPromptResponse = null }?.invoke()
                    return true
                }
                requests += frame.copy()
                try {
                    val method = frame.getString("method")
                    nativeSocketMethods.add(socketId to method)
                    val params = frame.optJSONObject("params") ?: JSONObject()
                    if (method == "client.capabilities") {
                        serverRequestCapabilities[this] = params.optBoolean("server_requests")
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to JSONObject()).toString())
                        return true
                    }
                    when (method) {
                        "prompt.submit", "session.steer" -> params.string("session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                        "session.active_list" -> params.string("current_session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                    }
                    val result = rpc(method, params)
                    val response = json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString()
                    if (method == "projects.get" && holdNextProjectDetail) {
                        holdNextProjectDetail = false
                        projectDetailCaptured.countDown()
                        // Real WebSocket.send enqueues; a held response must not block the Compose thread.
                        Thread {
                            val released = releaseProjectDetail.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            listener.onMessage(this, if (released) response else json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 500, "message" to "Synthetic project detail was not released")).toString())
                        }.apply { isDaemon = true; name = "beacon-project-test-response"; start() }
                        return true
                    }
                    val deferred = synchronized(delayedCreates) {
                        if (holdCreateAcknowledgement && method == "session.create" || holdPromptAcknowledgement && method == "prompt.submit" || holdSteerAcknowledgement && method == "session.steer" || holdReceiptAcknowledgement && method == "prompt.receipt" || holdActiveListAcknowledgement && method == "session.active_list" ||
                            holdConfigAcknowledgement && method == "config.set" || holdBranchAcknowledgement && method == "session.branch") { delayedCreates += { listener.onMessage(this, response) }; true } else false
                    }
                    if (!deferred) listener.onMessage(this, response)
                    if (!deferred && method == "session.events.since") {
                        val runtimeSessionId = params.string("session_id")
                        val reads = replayReads.computeIfAbsent(this) { AtomicInteger() }.incrementAndGet()
                        if (pendingNativeStreams.remove(runtimeSessionId)) {
                            activeNativeListener = listener
                            activeNativeSocket = this
                            activeNativeSessionId = runtimeSessionId
                            streamSubscriptions.incrementAndGet()
                        } else if (runtimeSessionId == activeNativeSessionId) {
                            // Like Hermes, live events go to the latest subscriber of the runtime (a reconnect re-subscribes on a new socket).
                            activeNativeListener = listener
                            activeNativeSocket = this
                        }
                        if (reads == 2 && runtimeSessionId == activeNativeSessionId) {
                            liveCollectorSocketId = socketId
                            liveCollectorSubscriptions.incrementAndGet()
                        }
                    }
                } catch (error: FixtureRpcFailure) {
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to error.code, "message" to error.message)).toString())
                } catch (error: IllegalArgumentException) {
                    val missing = error.message == "Synthetic session missing"
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to if (missing) 4007 else 404, "message" to if (missing) "session not found" else error.message)).toString())
                }
                return true
            }
        }
        // Like a network that is down: the connection never opens, so nothing can have been sent.
        if (refuseConnection) { refusedConnections.incrementAndGet(); listener.onFailure(socket, java.net.ConnectException("Synthetic connection refused"), null) }
        else {
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic wire").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to canonicalAuthority))).toString())
        }
        return socket
    }
    private fun rpc(method: String, params: JSONObject): JSONObject {
        check(params.string("profile") in if (profileIsolationBots) notificationProfiles else listOf("default", "coder"))
        if (profileIsolationBots && params.string("profile") in rejectedProfiles) throw FixtureRpcFailure(403, "Synthetic profile access denied")
        if (method != "session.resume" && params.string("session_id") in reapedRuntimes) throw FixtureRpcFailure(4001, "session not found")
        val id = params.string("session_id").let { runtimeStoredSessionIds[it] ?: it.removePrefix("runtime-") }
        return when (method) {
            "subagent.list" -> json("subagents" to JSONArray())
            "delegation.status" -> json("active" to JSONArray())
            "process.list" -> json("processes" to JSONArray())
            "session.list" -> json("sessions" to JSONArray(sessions.values.filter { !it.getJSONObject("info").optBoolean("archived") && (params.optBoolean("include_hidden") || !it.getJSONObject("info").optBoolean("hidden")) }.map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("stored_session_id", row.string("stored_session_id")) }))
            "projects.list" -> json("projects" to JSONArray(projects.values.toList()))
            // Like Hermes: the project owning a folder; no cwd means the profile's own (project-free) folder.
            "projects.for_cwd" -> if (projectLookupFailure) throw FixtureRpcFailure(5007, "Projects are unavailable") else params.string("cwd").ifBlank { "C:/synthetic-home" }.let { cwd -> json("cwd" to cwd, "project" to (projects.values.filter { project -> project.rows("folders").any { cwd.startsWith(it.string("path")) } }.maxByOrNull { project -> project.rows("folders").maxOfOrNull { it.string("path").length } ?: 0 } ?: JSONObject.NULL)) }
            "session.workspace.move" -> params.getString("session_key").let { key -> sessions.getValue(key).getJSONObject("info").put("cwd", params.getString("cwd")); json("session_key" to key, "cwd" to params.getString("cwd")) }
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
                if (profileSessionIds[params.string("profile")]?.contains(id) == false) throw IllegalArgumentException("Synthetic session missing")
                val row = sessions[id] ?: unpromptedShells.remove(id)?.also { sessions[id] = it }
                row?.copy()?.apply {
                    val runtime = if (canonicalAuthority) id else resumeRuntimeSessionIds[id] ?: string("session_id")
                    put("session_id", runtime)
                    runtimeStoredSessionIds[runtime] = id
                    currentRuntimeSessionIds[id] = runtime
                    put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id); if (omitResumeYolo) getJSONObject("info").remove("yolo")
                    if (canonicalAuthority) {
                        remove("session_key"); getJSONObject("info").remove("stored_session_id")
                        put("stored_session_id", id).put("execution_generation", 7).put("running", nativeTurnStatus in setOf("working", "waiting"))
                        getJSONObject("info").put("profile_name", params.string("profile")).apply {
                            if (omitCanonicalResumeProfile) remove("profile_id") else put("profile_id", canonicalProfileId(params.string("profile")))
                        }
                    }
                    put("messages_omitted", params.optBoolean("omit_messages"))
                    put("messages", JSONArray(if (params.optBoolean("omit_messages")) emptyList<JSONObject>() else rows("messages").map { message -> message.copy().apply {
                        put("text", nativeDisplayText(message, params.optBoolean("inline_images", true)))
                        remove("content"); remove("attachments")
                        if (has("id")) put("row_id", remove("id"))
                    } }))
                }
                    ?: throw IllegalArgumentException("Synthetic session missing")
            }
            "session.history" -> { require(!historyFailure) { "Synthetic history unavailable" }; sessions[id]?.copy()?.apply { put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id) } ?: throw IllegalArgumentException("Synthetic session missing") }
            "session.info" -> {
                require(canonicalAuthority && sessions.containsKey(id)) { "Synthetic session missing" }
                json("source" to "android", "lazy" to true, "desktop_protocol" to "hermes-gateway-v1", "profile_name" to params.string("profile")).apply {
                    if (!omitCanonicalInfoProfile) put("profile_id", canonicalProfileId(params.string("profile")))
                }
            }
            "session.create" -> { val key = "new-${sequence.incrementAndGet()}"; history(key, if (dropCreateTitle) "" else params.string("title", "New conversation"), JSONArray()).also { row ->
                row.getJSONObject("info").put("source", params.string("source")).put("model", params.string("model", profileModel))
                runtimeStoredSessionIds[row.string("session_id")] = key
                currentRuntimeSessionIds[key] = row.string("session_id")
                sessions[key] = row
            } }
            "session.events.since" -> json("epoch" to "synthetic", "latest_seq" to nativeEventSequence.get(),
                "events" to JSONArray(nativeEvents.filter { it.optLong("seq") > params.optLong("last_seen", 0) }),
                "open_requests" to JSONArray(openNativeServerRequests.values.filter { it.optJSONObject("params")?.string("session_id") == params.string("session_id") }))
            "session.active_list" -> {
                if (nativeStatusFailure) { nativeStatusProbes.add("failed"); throw IllegalArgumentException("Synthetic active status unavailable") }
                val observedStatus = nativeTurnStatus
                val runtime = params.optString("current_session_id")
                terminalOnActiveList?.takeIf { runtime.isNotBlank() }?.let { payload ->
                    terminalOnActiveList = null
                    retainNativeEvent(runtime, "message.complete", payload)
                    nativeTurnStatus = "idle"
                }
                val stored = runtimeStoredSessionIds[runtime] ?: runtime.takeIf { it.startsWith("runtime-") }?.removePrefix("runtime-")
                val rows = JSONArray()
                if (stored != null && sessions.containsKey(stored) && currentRuntimeSessionIds[stored]?.let { it == runtime } != false) rows.put(json("id" to runtime, "session_key" to stored, "status" to observedStatus))
                nativeStatusProbes.add(observedStatus)
                json("sessions" to rows)
            }
            "session.usage" -> json("input_tokens" to 100, "output_tokens" to 20, "context_window" to 32000)
            // Like Hermes for a live session: the agent reports "github-copilot" and the "copilot" row is the one marked current.
            "model.options" -> json("model" to profileModel, "provider" to "github-copilot", "providers" to JSONArray()
                .put(json("slug" to "other", "name" to "Other provider", "is_current" to false, "models" to JSONArray(listOf("synthetic-model"))))
                .put(json("slug" to "copilot", "name" to "GitHub Copilot", "is_current" to true, "models" to JSONArray(listOf("synthetic-model")))))
            "session.title" -> {
                // Hermes titles are unique per profile (idx_sessions_title_unique).
                renameFailure?.let { throw FixtureRpcFailure(500, it) }
                if (sessions.any { (key, row) -> key != id && row.getJSONObject("info").string("title") == params.getString("title") }) throw FixtureRpcFailure(4022, "Title already in use")
                sessions.getValue(id).getJSONObject("info").put("title", params.getString("title")); json("ok" to true)
            }
            "session.set_hidden" -> { val hidden = params.getBoolean("hidden"); sessions.getValue(id).getJSONObject("info").put("hidden", hidden); json("hidden" to hidden, "session_key" to id) }
            "session.archive" -> { val archived = params.getBoolean("archived"); sessions.getValue(id).getJSONObject("info").put("archived", archived); json("archived" to archived, "session_key" to id) }
            "session.close" -> { closedSessions.add(id); json("closed" to true) }
            "session.delete" -> {
                require(id in closedSessions) { "cannot delete an active session" }
                if (deleteMissingAfterClose) { sessions.remove(id); throw FixtureRpcFailure(4007, "session not found") }
                sessions.remove(id); json("deleted" to id)
            }
            "session.branch" -> { val key = "branch-${sequence.incrementAndGet()}"; val rows = sessions.getValue(id).getJSONArray("messages"); history(key, params.string("name", "Branch"), JSONArray((0 until params.optInt("count", rows.length()).coerceIn(0, rows.length())).map(rows::get))).also { sessions[key] = it } }
            "session.compress" -> json("status" to "aborted")
            "session.undo" -> { sessions.getValue(id).put("messages", JSONArray()); json("ok" to true) }
            "config.get" -> if (params.string("key") == "provider") json("model" to profileModel, "provider" to "fixture")
                else json("value" to when (params.string("key")) { "approvals.mode" -> if (profileIsolationBots) isolationApprovals.getValue(params.string("profile")) else approvalMode; "reasoning" -> params.string("session_id").takeIf(String::isNotBlank)?.let { sessionReasoning[id] } ?: reasoningEffort; else -> reasoningEffort })
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
                if (key == "reasoning") {
                    if (params.string("session_id").isBlank()) reasoningEffort = value else sessionReasoning[id] = value
                    if (params.string("session_id").isNotBlank()) {
                        sessions.getValue(id).getJSONObject("info").put("reasoning_effort", value)
                        sessions.getValue(id).put("model_config", JSONObject(sessions.getValue(id).optString("model_config").ifBlank { "{}" }).put("reasoning_config", json("enabled" to (value != "none"), "effort" to value)).toString())
                    }
                }
                check(value !in setOf("show", "hide"))
                if (key == "approvals.mode") {
                    require(!rejectApprovalWrite) { "Synthetic approval write refused" }
                    require(value in nativeApprovalModes)
                    if (profileIsolationBots) isolationApprovals[params.string("profile")] = value else approvalMode = value
                }
                if (key == "yolo") {
                    // Like Hermes: the live flag and the saved model_config.yolo_mode change together.
                    val on = value in setOf("1", "on", "true")
                    val row = sessions.getValue(id)
                    row.getJSONObject("info").put("yolo", on)
                    row.put("model_config", JSONObject(row.optString("model_config").ifBlank { "{}" }).put("yolo_mode", on).toString())
                }
                json("ok" to true, "key" to key, "value" to if (key == "yolo") if (value in setOf("1", "on", "true")) "1" else "0" else value)
            }
            "approval.pending" -> json("approvals" to JSONArray())
            "commands.catalog" -> {
                require(!commandCatalogFailure) { "Synthetic command catalog unavailable" }
                json("pairs" to JSONArray().apply { repeat(commandDistractors) { put(JSONArray().put("/candidate$it").put("The help text describes another command")) } }.put(JSONArray().put("/help").put("Show available commands")).put(JSONArray().put("/model").put("Choose a model")))
            }
            "prompt.submit" -> {
                val rowId = sequence.incrementAndGet()
                if (canonicalAuthority) {
                    require(params.string("submission_id").isNotBlank()) { "Canonical submission identity is required" }
                    val status = if (queueNextCanonicalSubmission.getAndSet(false)) "queued" else "started"
                    val admission = "admission-$rowId"
                    val result = json("admission_id" to admission, "ref" to json("profile_id" to canonicalProfileId(params.string("profile")), "session_id" to id),
                        "sequence" to rowId, "status" to status, "outcome" to null, "authority_epoch" to 1, "execution_generation" to if (status == "started") 7 else null)
                    canonicalAdmissions[admission] = result.copy()
                    nativeTurnStatus = if (status == "queued") "starting" else "working"
                    result
                } else {
                    nativeTurnStatus = "working"
                    if (persistTerminalMessages) sessions[id]?.optJSONArray("messages")?.put(json("role" to "user", "text" to params.string("text"), "row_id" to rowId))
                    json("status" to "streaming", "user_row_id" to rowId)
                }
            }
            "session.steer" -> { steerFailureCode?.let { throw FixtureRpcFailure(it, "Synthetic steer refusal") }; json("status" to steerStatus, "accepted" to (steerStatus != "rejected")) }
            "prompt.receipt" -> (canonicalAdmissions[params.string("admission_id")]?.copy()?.also {
                require(it.getJSONObject("ref").string("session_id") == id) { "Synthetic admission belongs to another session" }
            } ?: json("status" to admissionReceiptStatus, "outcome" to admissionReceiptOutcome, "admission_id" to params.string("admission_id"), "ref" to json("profile_id" to canonicalProfileId(params.string("profile")), "session_id" to id))).apply {
                    admissionReceiptIdOverride?.let { put("admission_id", it) }
                    val ref = getJSONObject("ref")
                    admissionReceiptRefOverride?.let { ref.put("session_id", it) }
                    admissionReceiptProfileOverride?.let { ref.put("profile_id", it) }
                }
            "session.interrupt" -> json("ok" to true)
            else -> throw IllegalArgumentException("Synthetic fixture has no response for $method")
        }
    }
}
