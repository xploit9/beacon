package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.waitsForOwner
import dev.hermes.android.voice.finishCallOnce
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A conversation binding names a runtime that lives on the socket that made it; Hermes reaps the runtime once that socket is gone. */
class NativeGatewayBindingTest {
    private class Wire(initial: AuthContext = AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), store: AuthStore? = null) : WebSocket.Factory {
        val methods = mutableListOf<String>()
        val httpRequests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val socketRequests = mutableListOf<Request>()
        val connectionMethods = mutableListOf<MutableList<String>>()
        val connectionFrames = mutableListOf<MutableList<JSONObject>>()
        val responses = mutableListOf<Pair<Int, JSONObject>>()
        var replayResult = json("epoch" to "epoch-a", "events" to JSONArray(), "latest_seq" to 0)
        lateinit var socket: WebSocket
        lateinit var listener: WebSocketListener
        var canonical = false
        var relayStatus = 404
        var relayBody = """{"detail":"Not Found"}"""
        var onTicket: (() -> Unit)? = null
        /** How many next socket upgrades the plugin refuses before they open (403, as Starlette answers a socket closed before accept). */
        var refuseUpgrades = 0
        /** The status a refused upgrade answers: 403 for the plugin's at-once refusals, 429 for a 1.2.1 socket that waited for a worker. */
        var refuseUpgradeStatus = 403
        /** File frames that came in pieces (canonical_transport.PIECE_*): how many pieces, and the largest. */
        var binaryPieces = 0
        var largestPiece = 0
        /** The plugin cannot take a file now (its memory budget is full, or this phone has another file in flight): it answers "busy" at the header. */
        var pieceBusy = false
        /** How many next files the plugin gives up on at their sixth piece ("stalled", follow-up 8), and the largest piece of each file's try. */
        var pieceStalls = 0
        val fileLargest = mutableListOf<Int>()
        /** The first piece of each file's try. */
        val fileFirst = mutableListOf<Int>()
        /** How many next sockets that carry a file's pieces are lost at their sixth piece (a link that cannot carry the file: Hermes' dashboard closes it). */
        var socketDrops = 0
        /** The chat's socket is lost under a file sent in one text frame (data_url), as Hermes' dashboard closes one whose pong waits behind it. */
        var loseSocketOnTextFile = false
        /** The chat's own socket is lost at a file socket's this-many-th piece (0: never): a deep drop on the one upload queue takes it with the file's pieces. */
        var loseChatAtFilePiece = 0
        /** Every socket lost, the tickets asked for a file's own socket ({"purpose": "file"}), and what those answer (null: as relayStatus). */
        val lostSockets = mutableListOf<WebSocket>()
        var fileTickets = 0
        var fileTicketStatus: Int? = null
        /** The plugin's rule across one phone's clients (canonical_transport file_phones): a newer file socket ends the older one. Shared by every Wire of one phone. */
        var filePhone: FilePhone? = null
        /** File answers withheld until this completes (a file whose upload takes longer than a socket's reopen). */
        var holdFileAnswers: CompletableDeferred<Unit>? = null
        /** How many next file sockets take a file's header and pieces and then say nothing: no acknowledgment, no answer, no close (a socket that stays open and answers pings). */
        var silentFileSockets = 0
        /** Every socket this wire's client cancelled. */
        val cancelled: MutableList<WebSocket> = java.util.Collections.synchronizedList(mutableListOf())
        var advertiseReady = true
        var strictCanonicalCreate = false
        /** How many next session.create frames the plugin answers busy itself (backlog 67: no worker in time; Hermes never sees them). */
        var busyCreates = 0
        var canonicalCreateResult: JSONObject? = null
        var canonicalResumeResult: JSONObject? = null
        var dropSteerResponse = false
        var dropAfterSteerRefresh = false
        val acceptedCreates = mutableListOf<JSONObject>()
        var deferActivation = false
        var pendingActivation: Pair<WebSocket, JSONObject>? = null
        var activationResult: JSONObject? = json("session_id" to "runtime-1", "stored_session_id" to "stored-a")
        @Volatile var runtime = "runtime-1"
        /** A runtime Hermes dropped: a call naming it answers 4001, as Hermes does for one that is not in memory. */
        @Volatile var reaped: String? = null
        /** Hermes' text for the 4001: "session not found" when the runtime is gone; other 4001s (a session not owned by this transport) keep the binding. */
        @Volatile var refusal = "session not found"
        val client = BeaconClient(initial, store, this) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                httpRequests += request
                val relay = request.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket"
                // A file's own socket (form 4) asks its ticket with {"purpose": "file"}; the plugin's answer says so.
                val file = relay && request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty().contains("\"purpose\":\"file\"")
                if (relay && !file) onTicket?.invoke()
                if (file) fileTickets++
                val status = if (file) fileTicketStatus ?: relayStatus else if (relay) relayStatus else 200
                val body = if (file && status == 200) JSONObject(relayBody).put("purpose", "file").put("ticket", "fixture-file-ticket").toString()
                    else if (relay) relayBody else json("ticket" to "fixture-ticket").toString()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            socketRequests += request
            val connectionCalls = mutableListOf<String>()
            connectionMethods += connectionCalls
            val connectionIndex = connectionMethods.lastIndex
            val connectionMessages = mutableListOf<JSONObject>()
            connectionFrames += connectionMessages
            // A file's own socket leaves the chat's socket and listener where they are, for the tests that speak on them.
            val fileSocket = request.header("Sec-WebSocket-Protocol").orEmpty().contains("fixture-file-ticket")
            val wire = this  // for a file socket's own fake to reach the chat's socket and listener
            val silent = fileSocket && silentFileSockets > 0 && run { silentFileSockets--; true }
            if (!fileSocket) this.listener = listener
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() { cancelled += this; if (fileSocket) filePhone?.release(this) }
                override fun close(code: Int, reason: String?): Boolean { if (fileSocket) filePhone?.release(this); return true }
                /** The plugin ended this file socket (a newer one of the same phone opened): it takes and answers nothing more. */
                @Volatile var ended = false
                private val pieces = okio.Buffer()
                private var header: JSONObject? = null
                private var refused = false
                private var ident = ""
                private var held = 0L
                private var filePieces = 0
                // As the plugin's relay (follow-ups 6 and 8): a header with the frame's text around the file's base64, then the base64, each piece acknowledged
                // with the base64 held; the frame is joined from them and Hermes gets it as text. A file it cannot take now is answered "busy" at the header,
                // one it gives up on "stalled", and its pieces are dropped.
                override fun send(bytes: ByteString): Boolean {
                    if (ended) return false
                    binaryPieces++; largestPiece = maxOf(largestPiece, bytes.size)
                    if (silent) return true  // taken, never acknowledged
                    when (bytes[0].toInt()) {
                        3 -> {
                            val head = JSONObject(bytes.substring(1).utf8()).also { header = it }
                            ident = JSONObject(head.getString("head") + head.getString("tail")).getString("id")
                            held = 0; filePieces = 0; fileLargest += 0; fileFirst += 0
                            refused = pieceBusy
                            if (refused) listener.onMessage(this, json("jsonrpc" to "2.0", "id" to ident,
                                "error" to json("code" to 503, "message" to "Canonical gateway busy", "data" to json("source" to "beacon"))).toString())
                            return true
                        }
                        0, 1 -> {
                            val last = bytes[0].toInt() == 1
                            if (refused) { if (last) { header = null; refused = false }; return true }
                            fileLargest[fileLargest.lastIndex] = maxOf(fileLargest.last(), bytes.size - 1)
                            if (fileFirst.last() == 0) fileFirst[fileFirst.lastIndex] = bytes.size - 1
                            if (fileSocket && loseChatAtFilePiece > 0 && filePieces + 1 == loseChatAtFilePiece) {
                                loseChatAtFilePiece = 0; lostSockets += wire.socket
                                wire.listener.onFailure(wire.socket, java.io.IOException("fixture lost the chat's socket"), null)
                            }
                            if (filePieces + 1 == 6 && socketDrops > 0) {
                                socketDrops--; lostSockets += this
                                listener.onFailure(this, java.io.IOException("fixture lost the socket carrying the file"), null)
                                return false
                            }
                            if (++filePieces == 6 && pieceStalls > 0) {
                                pieceStalls--; refused = !last; pieces.clear()
                                if (last) header = null
                                listener.onMessage(this, json("jsonrpc" to "2.0", "id" to ident,
                                    "error" to json("code" to 408, "message" to "File upload stalled", "data" to json("source" to "beacon"))).toString())
                                return true
                            }
                            pieces.write(bytes.substring(1)); held += bytes.size - 1
                            listener.onMessage(this, okio.Buffer().writeByte(4).writeLong(held).writeUtf8(ident).readByteString())
                            if (!last) return true
                            val head = header ?: return false
                            header = null
                            return send(head.getString("head") + pieces.readUtf8() + head.getString("tail"))
                        }
                        else -> return false
                    }
                }
                override fun send(text: String): Boolean {
                    if (ended) return false
                    val frame = JSONObject(text)
                    if (!frame.has("method")) { responses += connectionIndex to frame; return true }
                    connectionMessages += frame
                    val method = frame.getString("method")
                    methods += method
                    connectionCalls += method
                    if (loseSocketOnTextFile && method == "file.attach" && frame.getJSONObject("params").has("data_url")) {
                        lostSockets += this
                        listener.onFailure(this, java.io.IOException("fixture lost the socket under a file"), null)
                        return true
                    }
                    if (method == "session.steer" && dropSteerResponse) {
                        listener.onFailure(this, java.io.IOException("fixture lost response"), null)
                        return true
                    }
                    if (method == "session.create" && busyCreates > 0) {
                        busyCreates--
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"),
                            "error" to json("code" to 503, "message" to "Canonical gateway busy", "data" to json("source" to "beacon"))).toString())
                        return true
                    }
                    val gone = method != "session.resume" && frame.getJSONObject("params").optString("session_id") == reaped
                    val reply = json("jsonrpc" to "2.0", "id" to frame.getString("id"))
                    val params = frame.getJSONObject("params")
                    // Match the installed canonical closed create schema and its source Literal.
                    val canonicalCreateFields = setOf("profile", "request_id", "source", "cwd", "row_cwd", "model", "provider", "base_url", "api_key", "toolsets", "reasoning", "max_turns", "ignore_rules", "yolo", "safe_mode", "ignore_user_config", "skills", "checkpoints", "accept_hooks", "pass_session_id", "editor", "title", "hidden", "follow_profile_config", "service_tier", "cwd_explicit", "run_budget", "verbose", "client_env")
                    val invalidCreate = canonical && strictCanonicalCreate && method == "session.create" &&
                        (params.keys().asSequence().any { it !in canonicalCreateFields } ||
                            params.has("source") && params.optString("source") !in setOf("cli", "tui", "gui", "acp", "tool", "oneshot"))
                    if (gone || method == "session.activate" && activationResult == null || invalidCreate) reply.put("error", json("code" to 4001, "message" to if (invalidCreate) "invalid_params" else refusal))
                    else reply.put("result", when (method) {
                        "session.create" -> {
                            acceptedCreates += params.copy()
                            if (canonical && strictCanonicalCreate) canonicalCreateResult?.copy() ?: json("session_id" to "stored-a", "stored_session_id" to "stored-a",
                                "messages" to JSONArray(), "message_count" to 0, "running" to false, "authority_epoch" to 1,
                                "replay_epoch" to "fixture", "last_sequence" to 0, "subscription_id" to "fixture-subscription",
                                "revision" to 1, "execution_generation" to 1, "pending" to JSONArray(), "prompts" to JSONArray(),
                                "info" to json("desktop_protocol" to "hermes-gateway-v1", "profile_name" to "default"))
                            else json("session_id" to runtime, "stored_session_id" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        }
                        "session.resume" -> if (canonical) canonicalResumeResult?.copy() ?: json("session_id" to runtime, "stored_session_id" to "stored-a", "messages" to JSONArray(), "message_count" to 0, "running" to true, "authority_epoch" to 1, "replay_epoch" to "fixture", "last_sequence" to 0, "subscription_id" to "fixture-subscription", "revision" to 1, "execution_generation" to 7, "pending" to JSONArray(), "prompts" to JSONArray(), "info" to json("desktop_protocol" to "hermes-gateway-v1", "profile_name" to "default")) else json("session_id" to runtime, "session_key" to "stored-a", "messages" to JSONArray(), "info" to JSONObject())
                        "prompt.submit" -> if (canonical) json("admission_id" to "admission-1", "ref" to json("profile_id" to "fixture-home", "session_id" to runtime), "sequence" to 1, "status" to "started", "outcome" to JSONObject.NULL, "authority_epoch" to 1, "execution_generation" to 7) else json("status" to "streaming", "user_row_id" to 1)
                        "session.activate" -> activationResult!!
                        // gateway/session_ancillary.attach_file: staged under attachments/, its @file: reference back (quoted when the name has spaces).
                        "file.attach" -> params.optString("name").let { name ->
                            json("attached" to true, "name" to name, "path" to "/synthetic/attachments/$name", "ref_path" to "attachments/$name",
                                "ref_text" to "@file:" + "attachments/$name".let { if (it.any(Char::isWhitespace)) "\"$it\"" else it }, "uploaded" to true)
                        }
                        "session.events.since" -> replayResult.copy()
                        else -> JSONObject()
                    })
                    if (method == "session.activate" && deferActivation) pendingActivation = this to reply
                    else if (fileSocket && method == "file.attach" && holdFileAnswers != null) holdFileAnswers!!.invokeOnCompletion { if (!ended) listener.onMessage(this, reply.toString()) }
                    else listener.onMessage(this, reply.toString())
                    if (method == "session.resume" && dropAfterSteerRefresh && connectionCalls.count { it == "session.resume" } > 1)
                        listener.onFailure(this, java.io.IOException("fixture lost connection after refresh"), null)
                    return true
                }
            }
            if (!fileSocket) this.socket = socket
            if (refuseUpgrades > 0) {
                refuseUpgrades--
                listener.onFailure(socket, java.net.ProtocolException("Expected HTTP 101 response but was '$refuseUpgradeStatus'"),
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(refuseUpgradeStatus).message("Refused").build())
                return socket
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            if (advertiseReady) listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to if (canonical) json("session_authority" to true) else json())).toString())
            // The plugin accepted it: the phone's older file socket ends with a normal close.
            if (fileSocket) filePhone?.accept(socket) { socket.ended = true; listener.onClosing(socket, 1000, "a newer file socket") }
            return socket
        }
    }

    /** One phone's file sockets as the plugin keeps them (canonical_transport.file_phones): accepting a newer one ends the older one that is still open. */
    private class FilePhone {
        private var current: Pair<WebSocket, () -> Unit>? = null
        @Volatile var opened = 0
        @Volatile var ended = 0
        @Synchronized fun accept(socket: WebSocket, end: () -> Unit) { opened++; current?.let { ended++; it.second() }; current = socket to end }
        /** A socket that closed or was cancelled is no longer the phone's file socket. */
        @Synchronized fun release(socket: WebSocket) { if (current?.first === socket) current = null }
    }

    private fun relayTicket(profile: String = "default") = json(
        "ticket" to "fixture-relay-ticket", "path" to "/api/plugins/beacon/gateway/ws",
        "profile" to profile, "canonical" to true, "expires_in" to 25,
    )

    @Test(timeout = 15_000) fun relayTicketBindsProfileAndDeviceWithoutSendingNativeCredentialsOnSocket() = runBlocking {
        val origin = "https://native.example"
        val auths = listOf(
            AuthContext(origin, emptyList(), "owner", "default", backend = "hermes-dashboard",
                bearer = NativeBearerRecord("fixture-access", "fixture-refresh", 4_000_000_000, origin, "owner", "default", "basic")),
            AuthContext(origin, emptyList(), "local", "default", backend = "hermes-dashboard",
                loopbackSession = LoopbackSessionRecord("fixture-loopback", origin, "local", verified = true)),
        )
        val field = NativePairing::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(NativePairing)
        var deviceId = "phone-1"
        val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "getString") deviceId else error("Unexpected preference operation")
        } as android.content.SharedPreferences
        field.set(NativePairing, prefs)
        try {
            for (auth in auths) {
                val wire = Wire(auth).apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
                try {
                    wire.client.nativeRpc("status")
                    val admission = wire.httpRequests.single { it.url.encodedPath.endsWith("/gateway-ticket") }
                    assertEquals("POST", admission.method)
                    assertEquals("default", admission.url.queryParameter("beacon_profile"))
                    assertEquals("phone-1", admission.header("X-Beacon-Device-ID"))
                    assertTrue(admission.header("Authorization") != null || admission.header(HERMES_LOOPBACK_SESSION_HEADER) != null)
                    val socket = wire.socketRequests.single()
                    assertEquals("/api/plugins/beacon/gateway/ws", socket.url.encodedPath)
                    assertNull(socket.url.query)
                    assertEquals("beacon-canonical-v1, beacon-ticket.fixture-relay-ticket", socket.header("Sec-WebSocket-Protocol"))
                    assertEquals("phone-1", socket.header("X-Beacon-Device-ID"))
                    assertNull(socket.header("Authorization"))
                    assertNull(socket.header(HERMES_LOOPBACK_SESSION_HEADER))
                    val capabilities = wire.connectionFrames.single().first().getJSONObject("params")
                    assertEquals(setOf("server_requests"), capabilities.keys().asSequence().toSet())
                    assertEquals(true, capabilities.opt("server_requests"))
                    assertEquals("default", wire.connectionFrames.single().last().getJSONObject("params").getString("profile"))
                } finally { wire.client.closeNative() }
            }
            val changed = Wire(auths.first()).apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
            changed.onTicket = { deviceId = "phone-2" }
            try {
                assertTrue(runCatching { changed.client.nativeRpc("status") }.isFailure)
                assertTrue(changed.socketRequests.isEmpty())
            } finally { changed.client.closeNative() }
        } finally { field.set(NativePairing, previous) }
    }

    @Test(timeout = 15_000) fun malformedRelayTicketsNeverOpenAnySocketOrFallBack() = runBlocking {
        val invalid = listOf(
            relayTicket().put("path", "https://other.example/api/plugins/beacon/gateway/ws"),
            relayTicket().put("path", "/api/plugins/beacon/gateway/ws?other=1"),
            relayTicket().put("profile", "other"), relayTicket().put("profile", 1),
            relayTicket().put("canonical", "true"), relayTicket().put("canonical", false),
            relayTicket().put("expires_in", 0), relayTicket().put("expires_in", 26),
            relayTicket().put("expires_in", "25"), relayTicket().put("expires_in", 1.5),
            relayTicket().put("ticket", "bad ticket"), relayTicket().put("ticket", "x".repeat(1025)),
            relayTicket().put("ticket", JSONObject.NULL),
        )
        for (ticket in invalid) {
            val wire = Wire().apply { relayStatus = 200; relayBody = ticket.toString() }
            try {
                assertTrue(ticket.toString(), runCatching { wire.client.nativeRpc("status") }.isFailure)
                assertTrue(wire.socketRequests.isEmpty())
                assertEquals(listOf("/api/plugins/beacon/mobile/gateway-ticket"), wire.httpRequests.map { it.url.encodedPath })
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun canonicalReadinessPersistsAcrossDetachedAndRecreatedClients() = runBlocking {
        val origin = "https://native.example"
        val auth = AuthContext(origin, emptyList(), "owner", "default", backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", 4_000_000_000, origin, "owner", "default", "basic"))
        val storage = CanonicalStoreFixture()
        val first = Wire(auth, storage.newStore()).apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
        try {
            first.client.nativeRpc("status")
            first.relayStatus = 404; first.relayBody = """{"detail":"Not Found"}"""
            val detached = first.client.detached()
            try { assertTrue(runCatching { detached.nativeRpc("status") }.isFailure) } finally { detached.closeNative() }
            assertEquals(1, first.socketRequests.size)
            val recreated = Wire(auth, storage.newStore())
            try {
                assertTrue(runCatching { recreated.client.nativeRpc("status") }.isFailure)
                assertTrue(recreated.socketRequests.isEmpty())
                assertEquals(0, recreated.httpRequests.count { it.url.encodedPath == "/api/auth/ws-ticket" })
            } finally { recreated.client.closeNative() }
        } finally { first.client.closeNative() }
    }

    @Test(timeout = 15_000) fun unsuccessfulRelayAdmissionNeverPersistsCanonicalRequirement() = runBlocking {
        val storage = CanonicalStoreFixture()
        val wire = Wire(store = storage.newStore()).apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = false }
        try {
            assertTrue(runCatching { wire.client.nativeRpc("status") }.isFailure)
            assertFalse(storage.newStore().canonicalRequired(wire.client.scope))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun detachedTransientClientKeepsItsVerifiedCanonicalRequirement() = runBlocking {
        val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
        try {
            wire.client.nativeRpc("status")
            wire.relayStatus = 404; wire.relayBody = """{"detail":"Not Found"}"""
            val detached = wire.client.detached()
            try { assertTrue(runCatching { detached.nativeRpc("status") }.isFailure) } finally { detached.closeNative() }
            assertEquals(1, wire.socketRequests.size)
            assertEquals(0, wire.httpRequests.count { it.url.encodedPath == "/api/auth/ws-ticket" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun anotherClientCannotKeepUsingStockAfterCanonicalReadiness() = runBlocking {
        val wire = Wire()
        val canonical = wire.client.detached()
        try {
            wire.client.nativeRpc("status")
            wire.relayStatus = 200; wire.relayBody = relayTicket().toString(); wire.canonical = true
            canonical.nativeRpc("status")
            wire.relayStatus = 404; wire.relayBody = """{"detail":"Not Found"}"""
            assertTrue(runCatching { wire.client.nativeRpc("status") }.isFailure)
            assertEquals(listOf("/api/ws", "/api/plugins/beacon/gateway/ws"), wire.socketRequests.map { it.url.encodedPath })
            assertEquals(1, wire.httpRequests.count { it.url.encodedPath == "/api/auth/ws-ticket" })
        } finally { canonical.closeNative(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun relayUsesTheExactlySelectedNonDefaultProfile() = runBlocking {
        val auth = AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard")
        val wire = Wire(auth).apply { relayStatus = 200; relayBody = relayTicket("work").toString(); canonical = true }
        try {
            wire.client.nativeRpc("status")
            assertEquals("work", wire.httpRequests.single().url.queryParameter("beacon_profile"))
            val frames = wire.connectionFrames.single()
            assertEquals(listOf("client.capabilities", "status"), frames.map { it.getString("method") })
            assertEquals(setOf("server_requests"), frames.first().getJSONObject("params").keys().asSequence().toSet())
            assertEquals("work", frames.last().getJSONObject("params").getString("profile"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun expiredAndInterruptedTicketRequestsNeverOpenAStockOrRelaySocket() = runBlocking {
        for (interrupted in listOf(false, true)) {
            val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().put("expires_in", 1).toString() }
            wire.onTicket = { if (interrupted) throw java.net.SocketTimeoutException("fixture timeout") else Thread.sleep(1100) }
            try {
                assertTrue(runCatching { wire.client.nativeRpc("status") }.isFailure)
                assertTrue(wire.socketRequests.isEmpty())
                assertEquals(1, wire.httpRequests.size)
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun onlyInitialExplicitJsonRouteAbsenceUsesStockTransport() = runBlocking {
        val legacy = Wire()
        try {
            legacy.client.nativeRpc("status")
            assertEquals(listOf("/api/plugins/beacon/mobile/gateway-ticket", "/api/auth/ws-ticket"), legacy.httpRequests.map { it.url.encodedPath })
            assertEquals("/api/ws", legacy.socketRequests.single().url.encodedPath)
        } finally { legacy.client.closeNative() }
        for ((status, body) in listOf(401 to """{"detail":"Unauthorized"}""", 403 to """{"detail":"device_removed"}""",
            404 to "not json", 404 to """{'detail':'Not Found'}""", 404 to """{"detail":"Not Found"} trailing""",
            404 to """{"detail":"device_removed"}""", 405 to """{"detail":"Method Not Allowed"}""",
            501 to """{"detail":"Not Implemented"}""", 503 to """{"detail":"runtime_draining"}""",
            200 to "not json", 200 to relayTicket().toString().replace("\"", "'"),
            200 to (relayTicket().toString() + " trailing"))) {
            val wire = Wire().apply { relayStatus = status; relayBody = body }
            try {
                assertTrue("$status $body", runCatching { wire.client.nativeRpc("status") }.isFailure)
                assertTrue(wire.socketRequests.isEmpty())
                assertEquals(1, wire.httpRequests.size)
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun reconnectObtainsANewTicketAndRetainsCookieRotation() = runBlocking {
        val origin = "https://native.example"
        val auth = AuthContext(origin, listOf("__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"), "owner", "default", backend = "hermes-dashboard", nativeProvider = "basic")
        val wire = Wire(auth).apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
        wire.onTicket = {
            val rotated = requireNotNull(Cookie.parse(wire.client.url("/"), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))
            wire.client.http.cookieJar.saveFromResponse(wire.client.url("/"), listOf(rotated))
        }
        try {
            wire.client.nativeRpc("status")
            assertTrue(wire.client.auth.cookies.any { it.contains("=after") })
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture lost connection"), null)
            wire.relayBody = relayTicket().put("ticket", "second-ticket").toString()
            wire.client.nativeRpc("status")
            assertEquals(2, wire.httpRequests.count { it.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket" })
            assertTrue(wire.socketRequests[0].header("Sec-WebSocket-Protocol")!!.endsWith("fixture-relay-ticket"))
            assertTrue(wire.socketRequests[1].header("Sec-WebSocket-Protocol")!!.endsWith("second-ticket"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun relayWaitsForCanonicalReadyAndNeverDowngradesAfterIt() = runBlocking {
        val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true; advertiseReady = false }
        val request = async { runCatching { wire.client.nativeRpc("status") } }
        try {
            withTimeout(2_000) { while (wire.socketRequests.isEmpty()) yield() }
            assertEquals(listOf("client.capabilities"), wire.methods)
            wire.listener.onMessage(wire.socket, json("method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
            request.await().getOrThrow()
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture lost connection"), null)
            wire.relayStatus = 404; wire.relayBody = """{"detail":"Not Found"}"""
            assertTrue(runCatching { wire.client.nativeRpc("status") }.isFailure)
            assertEquals(1, wire.socketRequests.size)
            assertEquals(0, wire.httpRequests.count { it.url.encodedPath == "/api/auth/ws-ticket" })
        } finally { request.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun relayRejectsNonCanonicalReadyAndAdmissionIdentityChanges() = runBlocking {
        val wrongReady = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = false }
        try {
            assertTrue(runCatching { wrongReady.client.nativeRpc("status") }.isFailure)
            assertEquals(listOf("client.capabilities"), wrongReady.methods)
        } finally { wrongReady.client.closeNative() }
        for (mode in listOf("close", "profile", "aba")) {
            val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true }
            wire.onTicket = {
                if (mode == "close") wire.client.closeNative()
                else runBlocking { wire.client.bindProfile("other"); if (mode == "aba") wire.client.bindProfile("default") }
            }
            try {
                assertTrue(mode, runCatching { wire.client.nativeRpc("status") }.isFailure)
                assertTrue(mode, wire.socketRequests.isEmpty())
            } finally { wire.client.closeNative() }
        }
    }

    // Live 2026-10-27: the socket that created a chat closes once its first prompt is accepted, yet a background read reopened the gateway
    // and the old binding looked valid again, so Beacon kept asking a runtime Hermes had dropped.
    @Test(timeout = 15_000) fun aBindingMadeOnAClosedSocketIsNotRevivedWhenAnotherCallReopensTheGateway() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeCreate(json("source" to "android")).runtimeSessionId)
            wire.client.closeNative()
            wire.runtime = "runtime-2"
            wire.client.nativeRpc("subagent.list")
            assertEquals("A reopened socket holds no runtime of the closed one", "", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals("The stored conversation is resumed instead of the old runtime reused", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(1, wire.methods.count { it == "session.resume" })
            assertEquals("runtime-2", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aBindingStaysWhileItsSocketIsOpen() = runBlocking {
        val wire = Wire()
        try {
            val created = wire.client.nativeCreate(json("source" to "android"))
            assertEquals(created, wire.client.nativeSession("stored-a"))
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
            assertEquals(0, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRuntimeHermesNoLongerHoldsIsForgottenSoNoCallerAddressesItAgain() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"
            val failure = runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("", wire.client.nativeSessionSnapshot().string("session_id"))
            wire.reaped = null
            wire.runtime = "runtime-2"
            assertEquals("The next use resumes the stored conversation", "runtime-2", wire.client.nativeSession("stored-a").runtimeSessionId)
            assertEquals(2, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aNotOwnedRefusalKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            assertEquals("runtime-1", wire.client.nativeSession("stored-a").runtimeSessionId)
            wire.reaped = "runtime-1"; wire.refusal = "session not found or not owned by this transport"
            val failure = runCatching { wire.client.nativeRpc("subagent.list", json("session_id" to "runtime-1")) }.exceptionOrNull()
            assertEquals(4001, (failure as ApiFailure).status)
            assertEquals("Only Hermes' runtime-gone answer drops the binding", "runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRefusalNamingAnotherRuntimeKeepsTheBinding() = runBlocking {
        val wire = Wire()
        try {
            wire.client.nativeSession("stored-a")
            wire.reaped = "runtime-elsewhere"
            assertTrue(runCatching { wire.client.nativeRpc("process.list", json("session_id" to "runtime-elsewhere")) }.isFailure)
            assertEquals("runtime-1", wire.client.nativeSessionSnapshot().string("session_id"))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun reconnectAttachesTheSameRuntimeBeforeReplayWithoutResubmitting() = runBlocking {
        val wire = Wire()
        val first = wire.client.nativeSession("stored-a")
        wire.client.nativeRpc("prompt.submit", json("session_id" to first.runtimeSessionId, "text" to "fixture"))
        val collector = async { runCatching { wire.client.nativeEvents { } } }
        withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val replacement = async { runCatching { wire.client.nativeEvents { } } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.size < 2 || "session.events.since" !in wire.connectionMethods[1]) yield() }
            val calls = wire.connectionMethods[1]
            assertTrue("The replacement must activate before replay", calls.indexOf("session.activate") in 0 until calls.indexOf("session.events.since"))
            assertEquals(first, wire.client.nativeSessionSnapshot().let { nativeSessionBinding(wire.client.scope, it) })
            assertEquals("An accepted prompt is never resubmitted", 1, wire.methods.count { it == "prompt.submit" })
            assertEquals("Recovery must not resume a durable conversation", 1, wire.methods.count { it == "session.resume" })
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun missingOrForeignActivationCannotReplayOrClaimRecovery() = runBlocking {
        for (activation in listOf(null, json("session_id" to "runtime-foreign", "stored_session_id" to "stored-a"),
                json("session_id" to "runtime-1", "stored_session_id" to "foreign"))) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.activationResult = activation
            val replacement = async { runCatching { wire.client.nativeEvents { } } }
            try {
                withTimeout(2_000) { while (wire.connectionMethods.size < 2 || wire.connectionMethods[1].size < 2) yield() }
                assertEquals("Only activation may be attempted on the replacement", listOf("client.capabilities", "session.activate"), wire.connectionMethods[1])
                assertTrue("Unverified runtime cannot be exposed as live", wire.client.nativeSessionSnapshot().length() == 0)
                assertEquals(1, wire.methods.count { it == "session.resume" })
            } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun canonicalReconnectResubscribesWithTheSameDurableIdentity() = runBlocking {
        val wire = Wire().apply { canonical = true; runtime = "stored-a" }
        val binding = wire.client.nativeSession("stored-a")
        val collector = async { runCatching { wire.client.nativeEvents { } } }
        withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val replacement = async { runCatching { wire.client.nativeEvents { } } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.size < 2 || "session.events.since" !in wire.connectionMethods[1]) yield() }
            val methods = wire.connectionMethods[1]
            assertTrue(methods.indexOf("session.resume") in 0 until methods.indexOf("session.events.since"))
            assertFalse("Canonical authority does not support session.activate", "session.activate" in methods)
            assertEquals(binding, nativeSessionBinding(wire.client.scope, wire.client.nativeSessionSnapshot()))
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun cancelledOrClosedRecoveryCannotAdoptALateActivation() = runBlocking {
        for (ending in listOf("cancel", "close", "profile")) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.deferActivation = true
            val replacement = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.pendingActivation == null) yield() }
            when (ending) {
                "cancel" -> replacement.cancelAndJoin()
                "close" -> { wire.client.closeNative(); replacement.await() }
                else -> { wire.client.bindProfile("other"); replacement.await() }
            }
            val (socket, reply) = wire.pendingActivation!!
            wire.listener.onMessage(socket, reply.toString())
            assertTrue("An interrupted recovery must not claim the runtime", wire.client.nativeSessionSnapshot().length() == 0)
            assertFalse("An interrupted recovery must not replay", "session.events.since" in wire.connectionMethods[1])
            wire.client.closeNative()
        }
    }


    @Test(timeout = 15_000) fun prepareReplayAlsoReattachesBeforeReadingTheReplacementStream() = runBlocking {
        val wire = Wire()
        try {
            wire.client.nativeSession("stored-a")
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            wire.client.nativeReplay()
            val calls = wire.connectionMethods[1]
            assertTrue("Replay preparation must activate the original runtime first", calls.indexOf("session.activate") in 0 until calls.indexOf("session.events.since"))
            assertEquals("Replay preparation never resubmits work", 0, wire.methods.count { it == "prompt.submit" })
            assertEquals(1, wire.methods.count { it == "session.resume" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun recoveredReplayKeepsSequenceAndInteractiveRequestsOnTheReplacementSocket() = runBlocking {
        val wire = Wire()
        val seq1 = json("session_id" to "runtime-1", "seq" to 1, "type" to "message.delta", "payload" to json())
        val seq2 = json("session_id" to "runtime-1", "seq" to 2, "type" to "message.delta", "payload" to json())
        wire.replayResult = json("epoch" to "epoch-a", "events" to JSONArray().put(seq1), "latest_seq" to 1)
        wire.client.nativeSession("stored-a")
        val seen = mutableListOf<Long>()
        val firstSeen = CompletableDeferred<Unit>()
        val collector = async { runCatching { wire.client.nativeEvents { frame ->
            val seq = frame.optJSONObject("params")?.optLong("seq") ?: 0
            if (seq > 0) { seen += seq; firstSeen.complete(Unit) }
        } } }
        withTimeout(2_000) { firstSeen.await() }; yield()
        wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
        collector.await()
        val request = json("id" to "rpc-approval", "method" to "approval", "params" to json("session_id" to "runtime-1", "request_id" to "ask-1", "choices" to JSONArray().put("deny")))
        wire.replayResult = json("epoch" to "epoch-a", "events" to JSONArray().put(seq1).put(seq2), "latest_seq" to 2, "open_requests" to JSONArray().put(request))
        val secondSeen = CompletableDeferred<Unit>()
        val askSeen = CompletableDeferred<Unit>()
        val replacement = async { runCatching { wire.client.nativeEvents { frame ->
            if (frame.optString("method") == "approval") askSeen.complete(Unit)
            val seq = frame.optJSONObject("params")?.optLong("seq") ?: 0
            if (seq > 0) { seen += seq; if (seq == 2L) secondSeen.complete(Unit) }
        } } }
        try {
            withTimeout(2_000) { askSeen.await(); secondSeen.await() }
            assertEquals(listOf(1L, 2L), seen)
            val replay = wire.connectionFrames[1].single { it.optString("method") == "session.events.since" }
            assertEquals(1L, replay.getJSONObject("params").getLong("last_seen"))
            assertTrue(wire.client.nativeAnswerServerRequest("approval", "ask-1", "runtime-1", json("choice" to "deny")).optBoolean("ok"))
            assertEquals(1, wire.responses.size)
            assertEquals("Interactive answers belong to the replacement socket", 1, wire.responses.single().first)
        } finally { replacement.cancelAndJoin(); wire.client.closeNative() }
    }


    @Test(timeout = 15_000) fun replacementMustAdvertiseTheSameKnownAuthorityBeforeRecovery() = runBlocking {
        for (missingReady in listOf(false, true)) {
            val wire = Wire()
            wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
            wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null)
            collector.await()
            wire.canonical = !missingReady
            wire.advertiseReady = !missingReady
            try {
                val recovery = withTimeout(6_000) { runCatching { wire.client.nativeEvents { } } }
                assertTrue("Unknown or changed authority cannot recover", recovery.isFailure)
                assertEquals(listOf("client.capabilities"), wire.connectionMethods[1])
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun canonicalCreateBothEntryPointsUseTheClosedNativeContract() = runBlocking {
        for (direct in listOf(true, false)) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
            try {
                val options = json("title" to "Fixture title", "model" to "fixture-model", "provider" to "fixture-provider", "cwd" to "/fixture")
                val binding = if (direct) wire.client.nativeCreate(options) else wire.client.nativeSession(null)
                assertEquals("stored-a", binding.storedSessionId)
                assertEquals("stored-a", binding.runtimeSessionId)
                val params = wire.acceptedCreates.single()
                assertFalse(params.has("source")); assertFalse(params.has("close_on_disconnect"))
                assertEquals("default", params.getString("profile"))
                if (direct) for (key in listOf("title", "model", "provider", "cwd")) assertEquals(options.get(key), params.get(key))
                val snapshot = wire.client.nativeSessionSnapshot()
                assertEquals("stored-a", snapshot.getString("session_id")); assertEquals("stored-a", snapshot.getString("stored_session_id"))
                assertEquals("fixture-subscription", snapshot.getString("subscription_id"))
            } finally { wire.client.closeNative() }
        }
    }

    /**
     * CHAT-121 (device round 4): the plugin turns an admission away at once while its admission workers are taken (its ticket answers 503,
     * or the socket's upgrade is refused). A new chat asked during the app's startup connections met that and failed before Hermes saw it.
     * Nothing reached Hermes, so the admission is asked again and the create reaches Hermes exactly once.
     */
    @Test(timeout = 15_000) fun aBusyAdmissionIsAskedAgainAndTheCreateReachesHermesOnce() = runBlocking {
        val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true; strictCanonicalCreate = true; refuseUpgrades = 1 }
        var tickets = 0
        wire.onTicket = {
            tickets++
            if (tickets <= 2) { wire.relayStatus = 503; wire.relayBody = """{"detail":"Canonical gateway unavailable"}""" }
            else { wire.relayStatus = 200; wire.relayBody = relayTicket().toString() }
        }
        try {
            val binding = wire.client.post("/api/session/new", json("title" to "Fixture title"))
            assertEquals("stored-a", binding.getString("session_id"))
            assertEquals("Two busy tickets, one admitted socket refused before it opened, then one admitted", 4, tickets)
            assertEquals(2, wire.socketRequests.size)
            assertEquals("Created once", 1, wire.methods.count { it == "session.create" })
            assertEquals("Fixture title", wire.acceptedCreates.single().getString("title"))
        } finally { wire.client.closeNative() }
    }

    /**
     * Backlog 67 (CHAT-121 follow-up 11): a create the plugin has no worker for is answered on the socket with its busy error (the plugin used
     * to close the whole socket, 1013, and every other request on it). The app reads it as the plugin's busy answer, never as an outcome it
     * must reconcile, and the socket it came on carries the next request: asked again, the create reaches Hermes once.
     */
    @Test(timeout = 15_000) fun aCreateThePluginAnswersBusyLeavesItsSocketWorkingAndNothingToReconcile() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; busyCreates = 1 }
        try {
            val busy = runCatching { wire.client.nativeCreate(json("title" to "Fixture title")) }.exceptionOrNull()
            assertTrue("$busy", busy != null && nativeGatewayBusy(busy) && !dev.hermes.android.answerLost(busy))
            val binding = wire.client.nativeCreate(json("title" to "Fixture title"))
            assertEquals("stored-a", binding.storedSessionId)
            assertEquals("One socket carried both", 1, wire.socketRequests.size)
            assertEquals("Hermes created it once", 1, wire.acceptedCreates.size)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aPluginThatStaysBusyFailsWithItsOwnAnswerAndNothingReachesHermes() = runBlocking {
        val wire = Wire().apply { relayStatus = 503; relayBody = """{"detail":"Canonical gateway unavailable"}"""; canonical = true }
        try {
            val failure = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }.exceptionOrNull()
            assertTrue("The plugin's own refusal surfaces: $failure", failure is ApiFailure && failure.status == 503 && failure.plugin)
            assertEquals("Asked again after each pause, then the refusal stands", NATIVE_ADMISSION_BUSY_PAUSES_MS.size + 1,
                wire.httpRequests.count { it.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket" })
            assertTrue(wire.socketRequests.isEmpty())
            assertTrue(wire.methods.isEmpty())
        } finally { wire.client.closeNative() }
    }

    /** A 1.2.1 plugin waits for an admission worker before it answers "busy" (canonical_transport.bounded_call): the waits must not stack. */
    @Test(timeout = 15_000) fun aBusyAnswerAfterThePluginWaitedIsNotAskedAgain() = runBlocking {
        val wire = Wire().apply { relayStatus = 503; relayBody = """{"detail":"Canonical gateway busy"}"""; canonical = true }
        try {
            val failure = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }.exceptionOrNull()
            assertTrue("The plugin's busy answer surfaces: $failure", failure is ApiFailure && failure.status == 503 && failure.plugin && failure.message == "Canonical gateway busy")
            assertEquals("Asked once", 1, wire.httpRequests.count { it.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket" })
            assertTrue(wire.socketRequests.isEmpty())
            assertTrue(wire.methods.isEmpty())
        } finally { wire.client.closeNative() }
    }

    /** REVIEW-121B P3-3: an "unavailable" that came slowly is a gateway that is slow or down, not a 1.2.0 busy refusal; asking it again stacked ~25 s. */
    @Test(timeout = 15_000) fun anUnavailableAnswerThatCameSlowlyIsNotAskedAgain() = runBlocking {
        val wire = Wire().apply { relayStatus = 503; relayBody = """{"detail":"Canonical gateway unavailable"}"""; canonical = true }
        wire.onTicket = { Thread.sleep(NATIVE_ADMISSION_QUICK_MS + 100) }
        try {
            val failure = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }.exceptionOrNull()
            assertTrue("The plugin's answer surfaces: $failure", failure is ApiFailure && failure.status == 503 && failure.plugin)
            assertEquals("Asked once", 1, wire.httpRequests.count { it.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket" })
            assertTrue(wire.socketRequests.isEmpty()); assertTrue(wire.methods.isEmpty())
        } finally { wire.client.closeNative() }
    }

    /** REVIEW-121B P3-4: a 1.2.1 plugin refuses a socket that waited for a worker with 429, which is not asked again; its at-once 403 still is. */
    @Test(timeout = 15_000) fun aSocketRefusedAfterThePluginWaitedIsNotAskedAgainButAnAtOnceRefusalIs() = runBlocking {
        for ((status, sockets) in listOf(429 to 1, 403 to 2)) {
            val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true; refuseUpgrades = 1; refuseUpgradeStatus = status }
            try {
                val outcome = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }
                assertEquals("$status", sockets, wire.socketRequests.size)
                if (status == 429) {
                    assertTrue("$status surfaces", generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it is NativeAdmissionRefused && it.status == 429 })
                    assertTrue(wire.methods.none { it == "session.create" })
                } else assertEquals("Created once after the at-once refusal", 1, wire.methods.count { it == "session.create" })
            } finally { wire.client.closeNative() }
        }
    }

    /**
     * REVIEW-121B FU-P3-1: the quick-refusal rule timed the whole admission (the ticket, a new connection, the upgrade), so on a slow link or behind
     * an h2 proxy an at-once socket refusal came after more than a second and was never asked again. Only the step that failed is timed now.
     */
    @Test(timeout = 15_000) fun anAtOnceSocketRefusalAfterASlowTicketIsStillAskedAgain() = runBlocking {
        val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true; refuseUpgrades = 1; refuseUpgradeStatus = 403 }
        wire.onTicket = { Thread.sleep(NATIVE_ADMISSION_QUICK_MS + 100) }
        try {
            val outcome = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }
            assertEquals("Asked again after the at-once refusal: ${outcome.exceptionOrNull()}", 2, wire.socketRequests.size)
            assertEquals(1, wire.methods.count { it == "session.create" })
        } finally { wire.client.closeNative() }
    }

    /** REVIEW-121B FU-P3-2: a busy plugin read "Can't reach Hermes" (its socket's 429) or "Hermes is reconnecting" (its ticket's "Canonical gateway busy"). */
    @Test(timeout = 15_000) fun aBusyPluginReadsBusyNotUnreachable() = runBlocking {
        val busy = "Hermes is busy. Wait a moment and try again."
        val wire = Wire().apply { relayStatus = 200; relayBody = relayTicket().toString(); canonical = true; refuseUpgrades = 1; refuseUpgradeStatus = 429 }
        try {
            val refused = runCatching { wire.client.post("/api/session/new", json("title" to "Fixture title")) }.exceptionOrNull()
            assertEquals(busy, appErrorText(requireNotNull(refused)))
        } finally { wire.client.closeNative() }
        assertEquals(busy, appErrorText(ApiFailure(503, "Canonical gateway busy", plugin = true)))
        // Not busy: an at-once refusal is out of reach for now, and "unavailable" is a gateway that is down.
        assertEquals(APP_CONNECTION_ERROR, appErrorText(java.io.IOException("Native connection interrupted", NativeAdmissionRefused(403, java.io.IOException("refused")))))
        assertEquals("Hermes is reconnecting. Try again in a moment.", appErrorText(ApiFailure(503, "Canonical gateway unavailable", plugin = true)))
    }

    /**
     * CHAT-121 follow-up 4: a file larger than one 8 MiB frame goes to a plugin that says it takes them (its ticket's attach_frame_limit) in pieces,
     * streamed from the kept file, and Hermes gets the one whole file.attach frame; a plugin that says nothing (1.2.0) gets none of it.
     */
    @Test(timeout = 30_000) fun aLargeFileGoesInPiecesToAPluginThatTakesThemAndNotToOneThatDoesNot() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }); deleteOnExit() }
        val name = "\u0645\u0644\u0641 \u062a\u062c\u0631\u064a\u0628\u064a 2026.pdf"
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024).put("attach_image_limit", 25L * 1024 * 1024)
        val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString() }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            assertTrue(lease.attachLimits.pieces)
            val staged = wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to name),
                InlineFile(file, "data_url", "data:application/pdf;base64,"))
            assertEquals("@file:\"attachments/$name\"", staged.getString("ref_text"))
            val frame = wire.connectionFrames.flatten().single { it.optString("method") == "file.attach" }.getJSONObject("params")
            assertEquals("data:application/pdf;base64," + java.util.Base64.getEncoder().encodeToString(file.readBytes()), frame.getString("data_url"))
            assertEquals(name, frame.getString("name"))
            assertTrue("${wire.binaryPieces} pieces", wire.binaryPieces >= 12 && wire.largestPiece <= ATTACHMENT_PIECE_BYTES + 1)
        } finally { wire.client.closeNative() }
        val old = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = relayTicket().toString() }
        try {
            val binding = old.client.nativeSession("stored-a")
            val lease = old.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            assertFalse(lease.attachLimits.pieces)
            val refused = runCatching { old.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to name),
                InlineFile(file, "data_url", "data:application/pdf;base64,")) }.exceptionOrNull()
            assertTrue("$refused", refused is NativeTurnNotSent)
            assertEquals(0, old.binaryPieces)
            assertTrue(old.methods.none { it == "file.attach" })
        } finally { old.client.closeNative() }
    }

    /**
     * CHAT-121 follow-up 6: the plugin answers "busy" at a file's header when it cannot take it now (every phone's files at its memory budget, or this
     * phone's other file in flight). The phone stops streaming there, drops what it sent, nothing reaches Hermes, and the failure reads as the busy line.
     */
    @Test(timeout = 30_000) fun aFileThePluginCannotTakeNowIsBusyAndStopsAtItsHeader() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); pieceBusy = true }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            val failure = runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                InlineFile(file, "data_url", "data:application/pdf;base64,")) }.exceptionOrNull()
            assertTrue("$failure", failure is ApiFailure && nativeGatewayBusy(failure))
            assertEquals("Hermes is busy. Wait a moment and try again.", appErrorText(failure!!))
            assertTrue("stopped at its header: ${wire.binaryPieces} pieces", wire.binaryPieces <= 3)
            assertTrue(wire.methods.none { it == "file.attach" })
        } finally { wire.client.closeNative() }
    }

    /**
     * CHAT-121 follow-up 8: a file the plugin gave up on (its pieces stopped coming in time: "stalled") goes again from its header; after three stalls it is
     * not sent, and a queued message with it waits for the owner. Nothing of a stalled try reaches Hermes. Each try starts again from a small piece, a
     * quarter of the try before's largest at most, and grows only on its own acknowledgments (follow-up 10; this fake acknowledges at once).
     */
    @Test(timeout = 30_000) fun aStalledFileGoesAgainInSmallerPiecesAndIsNotSentAfterThreeStalls() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(9 * 1024 * 1024) { (it * 31 % 251).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        for (stalls in listOf(2, 3)) {
            val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); pieceStalls = stalls }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                val sent = runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                    InlineFile(file, "data_url", "data:application/pdf;base64,")) }
                assertEquals("$stalls: ${wire.fileLargest}", 3, wire.fileLargest.size)
                assertTrue("each try starts small: first ${wire.fileFirst}, largest ${wire.fileLargest}",
                    wire.fileFirst.drop(1).zip(wire.fileLargest).all { (first, before) -> first in 1..maxOf(ATTACHMENT_PIECE_MIN_BYTES, before / 4) })
                if (stalls == 2) {
                    assertTrue("${sent.exceptionOrNull()}", sent.isSuccess)
                    val frame = wire.connectionFrames.flatten().single { it.optString("method") == "file.attach" }.getJSONObject("params")
                    assertEquals("data:application/pdf;base64," + java.util.Base64.getEncoder().encodeToString(file.readBytes()), frame.getString("data_url"))
                } else {
                    val failure = sent.exceptionOrNull()
                    assertTrue("$failure", failure is AttachmentStalled && waitsForOwner(failure))
                    assertTrue(wire.methods.none { it == "file.attach" })
                }
            } finally { wire.client.closeNative() }
        }
    }

    /**
     * CHAT-121 follow-up 9: a file never takes the chat's socket down. Its pieces go on a socket of its own (its own ticket, attached to the same session), so a
     * link that cannot carry the file (Hermes' dashboard closes the socket whose pong waits behind it) loses that socket only: the chat's socket carries no
     * piece, is never lost, and its turn goes on on it. A lost try goes again on a new socket, from a small piece, at most three times; then the file is not
     * sent and a queued message with it waits for the person. On chat-121-9 the pieces went on the chat's socket, and each loss took it down.
     */
    @Test(timeout = 30_000) fun aFilesOwnSocketCarriesItAndLosingItNeverTakesTheChatSocketDown() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(9 * 1024 * 1024) { (it * 37 % 241).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        for (drops in listOf(2, 3)) {
            val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); socketDrops = drops }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val chat = wire.socket
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                val sent = runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                    InlineFile(file, "data_url", "data:application/pdf;base64,")) }
                assertEquals("$drops sockets lost", drops, wire.lostSockets.size)
                assertTrue("the chat's socket is never lost: ${wire.lostSockets}", wire.lostSockets.none { it === chat })
                assertTrue("the chat's socket carries no file", wire.connectionMethods.first().none { it == "file.attach" })
                val chatTickets = wire.httpRequests.count { it.url.encodedPath.endsWith("/gateway-ticket") } - wire.fileTickets
                assertEquals("the chat's socket is the first and only one it opened", 1, chatTickets)
                assertEquals("one file socket a try", 3, wire.fileTickets)
                assertTrue("each try starts small: first ${wire.fileFirst}, largest ${wire.fileLargest}",
                    wire.fileFirst.drop(1).zip(wire.fileLargest).all { (first, before) -> first in 1..maxOf(ATTACHMENT_PIECE_MIN_BYTES, before / 4) })
                // Each file socket joined the chat's session before its file.
                wire.connectionMethods.drop(1).forEach { assertEquals(listOf("client.capabilities", "session.resume"), it.take(2)) }
                if (drops == 2) {
                    assertTrue("${sent.exceptionOrNull()}", sent.isSuccess)
                    val frame = wire.connectionFrames.flatten().single { it.optString("method") == "file.attach" }.getJSONObject("params")
                    assertEquals("data:application/pdf;base64," + java.util.Base64.getEncoder().encodeToString(file.readBytes()), frame.getString("data_url"))
                } else {
                    val failure = sent.exceptionOrNull()
                    assertTrue("$failure", failure is AttachmentStalled && waitsForOwner(failure))
                    assertTrue(wire.methods.none { it == "file.attach" })
                }
                // The turn goes on, on the chat's same socket.
                val submitted = runCatching { wire.client.nativeTurnRpc(lease, "prompt.submit", json("session_id" to binding.runtimeSessionId, "text" to "after the file", "submission_id" to "sub-$drops")) }
                assertTrue("${submitted.exceptionOrNull()}", submitted.isSuccess)
                assertEquals("prompt.submit", wire.connectionMethods.first().last())
            } finally { wire.client.closeNative() }
        }
    }

    /**
     * CHAT-121 follow-up 9: the chat's own socket lost while a file's pieces are on its own socket (a phone's upload is one queue, so a sudden deep drop can take
     * the chat's socket with them). The file is not sent and a queued message with it waits for the person, so it cannot happen again by itself; it was a
     * plain "not sent" before, which the queue sends again 5 s later. Lost before any piece went, it stays the chat's own change.
     */
    @Test(timeout = 30_000) fun theChatsSocketLostWhileAFilesPiecesAreOnTheWayStopsTheMessage() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(2 * 1024 * 1024) { (it * 13 % 239).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); loseChatAtFilePiece = 4 }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val chat = wire.socket
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            val failure = runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                InlineFile(file, "data_url", "data:application/pdf;base64,")) }.exceptionOrNull()
            assertTrue("the chat's socket was the one lost", wire.lostSockets.single() === chat)
            assertTrue("$failure", failure is AttachmentStalled && waitsForOwner(failure))
            assertEquals("no other try: the chat it was for is gone", 1, wire.fileTickets)
            assertTrue(wire.methods.none { it == "file.attach" })
        } finally { wire.client.closeNative() }
    }

    /**
     * JOINBFIX-121 (REVIEW-JOINB-121 P2-1): two files sent at once from one phone, each on its own detached client (two chats whose "Not sent" messages the
     * network's return sends together), must not end each other's file socket. The plugin keeps one file socket per phone and ends the older when a newer
     * opens, so with both on the way each reopen ended the other's, and each file was "Not sent" after three. One phone has one file on the way: the second
     * waits for the first. Each file here takes longer than a reopen (Hermes answers only once the test lets it); on the base tree the first ends "stalled".
     */
    @Test(timeout = 30_000) fun twoFilesFromOnePhoneGoOneAfterTheOtherAndNeitherEndsTheOthersSocket() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(300 * 1024) { (it * 29 % 247).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        val phone = FilePhone()
        val hold = CompletableDeferred<Unit>()
        val wires = List(2) { Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); filePhone = phone; holdFileAnswers = hold } }
        try {
            val sends = wires.map { wire ->
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                async(Dispatchers.IO) { runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                    InlineFile(file, "data_url", "data:application/pdf;base64,")) } }
            }
            // Both are on the way. Until the second file socket opens (the base tree: at once) the first waits for Hermes' answer; with one file at a time the
            // second never opens before the first is answered, so the wait ends at its bound.
            withTimeoutOrNull(1_500) { while (phone.opened < 2) delay(10) }
            delay(300)
            hold.complete(Unit)
            val results = sends.awaitAll()
            results.forEachIndexed { at, sent -> assertTrue("file $at: ${sent.exceptionOrNull()} (${phone.opened} file sockets opened, ${phone.ended} ended)", sent.isSuccess) }
            assertEquals("a file socket is never ended by a newer one", 0, phone.ended)
            assertEquals("one file socket each", listOf(1, 1), wires.map { it.fileTickets })
            wires.forEach { wire -> assertEquals(1, wire.connectionFrames.flatten().count { it.optString("method") == "file.attach" }) }
        } finally { hold.complete(Unit); wires.forEach { it.client.closeNative() } }
    }

    /**
     * JOINBFIX-121 follow-up 1: the process-wide file lock is never held for ever. The first file's socket takes its pieces and then goes silent (open, no
     * acknowledgment, no answer: a hung relay, or a link that answers pings and carries nothing). With no bound the first file waits for ever and the second
     * never goes. With one (the knob is shortened here from 60 s), each silent try is lost after it, the lock is let go between tries so the second file goes
     * after the first one's first lost try, and the first file ends stalled once its tries are spent.
     */
    @Test(timeout = 30_000) fun aFilesSocketThatGoesSilentDoesNotHoldTheLockForEverAndTheNextFileGoes() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(300 * 1024) { (it * 29 % 247).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        val phone = FilePhone()
        val first = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); filePhone = phone; silentFileSockets = ATTACHMENT_STALL_TRIES }
        val second = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString(); filePhone = phone }
        val shortest = attachmentSilenceMs
        attachmentSilenceMs = 400
        val doneAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val sends = ArrayList<Deferred<Result<JSONObject>>>()
        try {
            fun start(name: String, wire: Wire) = async(Dispatchers.IO) {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                    InlineFile(file, "data_url", "data:application/pdf;base64,")) }.also { doneAt[name] = System.nanoTime() }
            }.also { sends += it }
            val one = start("first", first)
            withTimeout(10_000) { while (first.binaryPieces == 0) delay(10) }  // the first file's pieces are on the way: it holds the lock
            val two = start("second", second)
            val sentSecond = withTimeoutOrNull(8_000) { two.await() }
            val sentFirst = withTimeoutOrNull(8_000) { one.await() }
            assertNotNull("the second file never went: the first held the lock for ever", sentSecond)
            assertTrue("the second file is sent: ${sentSecond!!.exceptionOrNull()}", sentSecond.isSuccess)
            assertNotNull("the first file never ended", sentFirst)
            assertTrue("the first file ends stalled once its tries are spent: ${sentFirst!!.exceptionOrNull()}", sentFirst.exceptionOrNull() is AttachmentStalled)
            assertEquals("each of its tries opened a socket", ATTACHMENT_STALL_TRIES, first.fileTickets)
            assertTrue("the second went between the first's tries, not after them", doneAt.getValue("second") < doneAt.getValue("first"))
            assertEquals("one file socket each time, none ended by a newer one", 0, phone.ended)
        } finally { attachmentSilenceMs = shortest; sends.forEach { it.cancel() }; first.client.closeNative(); second.client.closeNative() }
    }

    /**
     * JOINBFIX-121 (REVIEW-JOINB-121 P3-3): a send cancelled while its file socket opens (the wait for Hermes' ready, up to 15 s) cancels that socket, so
     * it does not hold one of the plugin's file sockets, and this phone's slot, until the plugin's quiet time.
     */
    @Test(timeout = 30_000) fun aSendCancelledWhileItsFileSocketOpensCancelsThatSocket() = runBlocking {
        val file = java.io.File.createTempFile("synthetic", ".pdf").apply { writeBytes(ByteArray(64 * 1024) { (it * 17 % 233).toByte() }); deleteOnExit() }
        val limits = relayTicket().put("attach_frame_limit", 384L * 1024 * 1024).put("attach_pieces", ATTACHMENT_PIECES_FORM).put("attach_file_limit", 256L * 1024 * 1024)
        val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = limits.toString() }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            wire.advertiseReady = false  // the file's socket opens, and Hermes' ready never comes
            val send = launch(Dispatchers.IO) { runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId, "name" to "a.pdf"),
                InlineFile(file, "data_url", "data:application/pdf;base64,")) } }
            withTimeoutOrNull(5_000) { while (wire.socketRequests.size < 2) delay(10) }
            assertEquals("the file's own socket was opened", 2, wire.socketRequests.size)
            assertTrue("the wait for ready has begun", wire.cancelled.isEmpty())
            send.cancelAndJoin()
            assertEquals("the socket the cancelled send opened is cancelled", 1, wire.cancelled.size)
            assertTrue(wire.cancelled.single().request().header("Sec-WebSocket-Protocol").orEmpty().contains("fixture-file-ticket"))
        } finally { wire.client.closeNative() }
    }

    /**
     * CHAT-121 follow-up 9: on a plugin without file sockets (1.2.0) a file goes in one text frame on the chat's socket. When that socket is lost under it
     * (Hermes' dashboard closes it on a link too slow for the file), the file is not sent and a queued message with it waits for the person: the queue
     * never sends it again by itself, so the socket is lost at most once in a row for it. On chat-121-9 it was an unknown outcome, sent again by the queue.
     */
    @Test(timeout = 15_000) fun aFileInOneTextFrameWhoseSocketIsLostIsNotSentAgainByItself() = runBlocking {
        val wire = Wire().apply { canonical = true; runtime = "stored-a"; relayStatus = 200; relayBody = relayTicket().toString(); loseSocketOnTextFile = true }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            val failure = runCatching { wire.client.nativeTurnRpc(lease, "file.attach", json("session_id" to binding.runtimeSessionId,
                "data_url" to "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString("synthetic note".toByteArray()), "name" to "note.txt")) }.exceptionOrNull()
            assertEquals(1, wire.lostSockets.size)
            assertTrue("$failure", failure is AttachmentStalled && waitsForOwner(failure))
            assertFalse("a file's own failure says nothing about reaching Hermes", requestNeverSent(failure!!))
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun anAdmissionRefusedForAnotherReasonIsNotAskedAgain() = runBlocking {
        for ((status, detail) in listOf(500 to "Hermes couldn't do that", 401 to "Verified native session required")) {
            val wire = Wire().apply { relayStatus = status; relayBody = json("detail" to detail).toString(); canonical = true }
            try {
                val failure = runCatching { wire.client.nativeRpc("status") }.exceptionOrNull()
                assertTrue("$status stays what it was: $failure", generateSequence(failure) { it.cause }.any { it is ApiFailure && it.status == status })
                assertEquals(1, wire.httpRequests.count { it.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket" })
                assertTrue(wire.methods.isEmpty())
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun explicitCanonicalSourceIsPreservedAndThreadsFailBeforeCreate() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
        try {
            val b = wire.client.nativeCreate(json("source" to "tui"))
            assertEquals("stored-a", b.storedSessionId)
            assertEquals("tui", wire.acceptedCreates.single().getString("source"))
            for (thread in listOf("android_thread", "phone")) {
                val before = wire.methods.count { it == "session.create" }
                assertTrue(runCatching { wire.client.nativeCreate(json("source" to thread)) }.isFailure)
                assertEquals("An unsupported thread must never be created as an ordinary chat", before, wire.methods.count { it == "session.create" })
            }
            val before = wire.methods.count { it == "session.create" }
            assertTrue(runCatching { wire.client.nativeRpc("session.create", json("close_on_disconnect" to true)) }.isFailure)
            assertEquals("A meaningful unsupported close policy cannot be silently discarded or sent", before, wire.methods.count { it == "session.create" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun aThreadOnAnAuthorityGatewayIsMadeAsATitledChatAndMarkedAtOnce() = runBlocking {
        ThreadMarks.reset()
        // The installed authority refuses any source that is not one of its surfaces (strictCanonicalCreate mirrors gateway/session_policy.py SURFACES).
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
        try {
            val created = wire.client.post("/api/session/new", json("title" to "News", "thread" to true))
            val params = wire.acceptedCreates.single()
            assertFalse("The authority must not be sent the thread source", params.has("source"))
            assertEquals("News", params.getString("title"))
            assertEquals("The thread is a thread in the list from the start", "android_thread", created.getString("source"))
            assertEquals("News", created.getString("title"))
            assertEquals(setOf("stored-a"), ThreadMarks.ids(wire.client.scope))
            assertTrue("The plugin keeps the mark for the owner's other phones",
                wire.httpRequests.any { it.method == "POST" && it.url.encodedPath == "/api/plugins/beacon/mobile/sessions/stored-a/thread" })
            assertEquals("Created once", 1, wire.methods.count { it == "session.create" })
        } finally { wire.client.closeNative(); ThreadMarks.reset() }
    }

    @Test(timeout = 15_000) fun aThreadOnAGatewayThatStoresTheSourceKeepsItAndNeedsNoMark() = runBlocking {
        ThreadMarks.reset()
        val wire = Wire()
        try {
            val created = wire.client.post("/api/session/new", json("title" to "News", "thread" to true))
            assertEquals("android_thread", wire.acceptedCreates.single().getString("source"))
            assertEquals("android_thread", created.getString("source"))
            assertTrue(ThreadMarks.ids(wire.client.scope).isEmpty())
            assertTrue(wire.httpRequests.none { it.url.encodedPath.endsWith("/thread") })
        } finally { wire.client.closeNative(); ThreadMarks.reset() }
    }

    @Test(timeout = 15_000) fun aRefusedThreadCreateIsNeverReportedAsAnUnreachableHermes() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true }
        try {
            val error = runCatching { wire.client.nativeCreate(json("title" to "News", "source" to "android_thread")) }.exceptionOrNull()
            assertTrue(error is NativeThreadUnsupported)
            assertEquals(THREAD_UNSUPPORTED_ERROR, appErrorText(error!!))
            assertNotEquals(APP_CONNECTION_ERROR, appErrorText(error))
            assertEquals(0, wire.methods.count { it == "session.create" })
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun createWaitsForTheIssuingSocketReadyBeforeSending() = runBlocking {
        val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; advertiseReady = false }
        val created = async { runCatching { wire.client.nativeCreate(json("title" to "Fixture")) } }
        try {
            withTimeout(2_000) { while (wire.connectionMethods.isEmpty()) yield() }
            yield()
            assertFalse("Create cannot be sent before authority readiness", "session.create" in wire.methods)
            wire.listener.onMessage(wire.socket, json("method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
            assertEquals("stored-a", created.await().getOrThrow().storedSessionId)
            assertEquals(1, wire.acceptedCreates.size)
        } finally { created.cancelAndJoin(); wire.client.closeNative() }
    }

    @Test(timeout = 20_000) fun unknownOrCancelledCreateReadinessNeverSendsAMutation() = runBlocking {
        for (ending in listOf("unknown", "cancel", "close", "profile")) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; advertiseReady = false }
            val created = async { runCatching { wire.client.nativeSession(null) } }
            try {
                withTimeout(2_000) { while (wire.connectionMethods.isEmpty()) yield() }
                yield()
                when (ending) {
                    "unknown" -> withTimeout(6_000) { created.await() }
                    "cancel" -> created.cancelAndJoin()
                    "close" -> { wire.client.closeNative(); created.await() }
                    else -> { wire.client.bindProfile("other"); created.await() }
                }
                assertFalse("No create may cross unverified readiness or a changed scope", "session.create" in wire.methods)
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { created.cancelAndJoin(); wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun legacyCreateRetainsItsExistingParameters() = runBlocking {
        for (thread in listOf(false, true)) {
            val wire = Wire()
            try {
                val input = json("title" to "Fixture").apply { if (thread) put("source", "android_thread") }
                val binding = wire.client.nativeCreate(input)
                assertEquals("runtime-1", binding.runtimeSessionId)
                assertEquals("stored-a", binding.storedSessionId)
                val params = wire.acceptedCreates.single()
                assertEquals(if (thread) "android_thread" else "android", params.getString("source"))
                assertFalse(params.getBoolean("close_on_disconnect"))
                assertEquals("default", params.getString("profile")); assertEquals("Fixture", params.getString("title"))
            } finally { wire.client.closeNative() }
        }
    }


    @Test(timeout = 15_000) fun malformedCanonicalCreateSnapshotIsUnknownWithoutRetryOrGuessedCleanup() = runBlocking {
        for (result in listOf(json("session_id" to "runtime-foreign", "stored_session_id" to "stored-a"),
                json("session_id" to "stored-a"), json("session_id" to "", "stored_session_id" to "stored-a"),
                json("session_id" to 123, "stored_session_id" to 123),
                json("session_id" to JSONObject.NULL, "stored_session_id" to "stored-a"),
                json("session_id" to "stored-a", "stored_session_id" to JSONObject.NULL),
                json("session_id" to "stored-a", "stored_session_id" to "stored-a", "info" to json("profile_name" to "foreign")),
                json("session_id" to "stored-a", "stored_session_id" to "stored-a", "info" to json("profile_name" to 7)))) {
            val wire = Wire().apply { canonical = true; strictCanonicalCreate = true; canonicalCreateResult = result }
            try {
                val error = runCatching { wire.client.nativeCreate(JSONObject()) }.exceptionOrNull()
                assertNotNull("Canonical create must not bind a malformed snapshot", error)
                assertEquals("A sent create with invalid identity has unknown outcome", NATIVE_OUTCOME_UNKNOWN, error!!.message)
                assertFalse(requestNeverSent(error))
                assertEquals("No automatic retry after a create was sent", 1, wire.methods.count { it == "session.create" })
                assertTrue("No guessed cleanup for an untrusted identity", wire.methods.none { it == "session.close" || it == "session.resume" })
                assertTrue(wire.client.nativeSessionSnapshot().length() == 0)
            } finally { wire.client.closeNative() }
        }
    }


    @Test fun canonicalResumeRejectsConflictingIdentitiesBeforeBindingOrAnyMutation() = runBlocking {
        for (result in listOf(json("session_id" to "foreign", "stored_session_id" to "stored-a"),
            json("session_id" to "stored-a", "stored_session_id" to "foreign"),
            json("session_id" to 123, "stored_session_id" to 123))) {
            val wire = Wire().apply { canonical = true; canonicalResumeResult = result }
            try {
                assertTrue(runCatching { wire.client.nativeSession("stored-a") }.isFailure)
                assertEquals(listOf("client.capabilities", "session.resume"), wire.methods)
                assertEquals(0, wire.client.nativeSessionSnapshot().length())
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun leasedSteerKeepsTheLiveCollectorAndUsesActualCanonicalGeneration() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val binding = wire.client.nativeSession("stored-a")
            val collector = async { runCatching { wire.client.nativeEvents { } } }
            try {
                withTimeout(2_000) { while (wire.methods.count { it == "session.events.since" } < 1) yield() }
                val beforeReplay = wire.methods.count { it == "session.events.since" }
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                assertEquals(canonical, lease.authority)
                assertEquals(binding, lease.binding)
                wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture"))
                val calls = wire.connectionFrames.single()
                val steer = calls.single { it.optString("method") == "session.steer" }.getJSONObject("params")
                assertEquals("default", steer.getString("profile"))
                assertEquals(binding.runtimeSessionId, steer.getString("session_id"))
                if (canonical) {
                    assertEquals(7L, steer.getLong("execution_generation"))
                    assertEquals(2, calls.count { it.optString("method") == "session.resume" })
                    assertTrue(calls.indexOfLast { it.optString("method") == "session.resume" } < calls.indexOfLast { it.optString("method") == "session.steer" })
                } else {
                    assertFalse(steer.has("execution_generation"))
                    assertEquals(1, calls.count { it.optString("method") == "session.resume" })
                }
                assertEquals("A steer cannot reset replay", beforeReplay, wire.methods.count { it == "session.events.since" })
                assertTrue("The stream collector remains active", collector.isActive)
                assertEquals(1, wire.methods.count { it == "session.steer" })
            } finally { collector.cancelAndJoin(); wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun canonicalSteerRequiresAValidRunningSnapshotGeneration() = runBlocking {
        val invalid = listOf(
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to false, "execution_generation" to 7),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true, "execution_generation" to 7.5),
            json("session_id" to "stored-a", "stored_session_id" to "foreign", "running" to true, "execution_generation" to 7),
            json("session_id" to "stored-a", "stored_session_id" to "stored-a", "running" to true, "execution_generation" to 7, "info" to json("profile_name" to "foreign"))
        )
        for (result in invalid) {
            val wire = Wire().apply { canonical = true; runtime = "stored-a" }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                wire.canonicalResumeResult = result
                val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
                assertTrue("Invalid or foreign snapshots cannot send a steer", error is NativeTurnNotSent)
                assertEquals(0, wire.methods.count { it == "session.steer" })
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun leasedMutationRejectsForeignOrReplacedConversationBeforeSend() = runBlocking {
        for (changed in listOf("runtime", "other-client", "cursor", "closed", "replaced", "profile")) {
            val wire = Wire()
            try {
                val binding = wire.client.nativeSession("stored-a")
                if (changed == "runtime") {
                    assertTrue(runCatching { wire.client.nativeTurnLease("stored-a", "foreign") }.exceptionOrNull() is NativeTurnNotSent)
                    continue
                }
                val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
                val target = if (changed == "other-client") Wire().client else wire.client
                when (changed) {
                    "cursor" -> wire.client.nativeCreate(JSONObject())
                    "closed" -> wire.client.closeNative()
                    "replaced" -> { wire.listener.onFailure(wire.socket, java.io.IOException("fixture loss"), null); wire.client.nativeRpc("subagent.list") }
                    "profile" -> wire.client.bindProfile("other")
                }
                val error = runCatching { target.nativeTurnRpc(lease, "prompt.submit", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
                assertTrue("A foreign or invalidated lease must fail before mutation: $changed", error is NativeTurnNotSent)
                assertEquals(0, wire.methods.count { it == "prompt.submit" })
                if (target !== wire.client) target.closeNative()
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun lostLeasedMutationResponseRemainsUnknown() = runBlocking {
        val wire = Wire().apply { dropSteerResponse = true }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease("stored-a", binding.runtimeSessionId)
            val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
            assertNotNull(error)
            assertFalse("A sent steer with a lost response is not a not-sent error", error is NativeTurnNotSent)
            assertFalse(requestNeverSent(error!!))
            assertEquals(1, wire.methods.count { it == "session.steer" })
        } finally { wire.client.closeNative() }
    }


    @Test(timeout = 15_000) fun leasedPromptSubmitsOnceOnItsBoundConnection() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            try {
                val binding = wire.client.nativeSession("stored-a")
                val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
                val params = json("session_id" to binding.runtimeSessionId, "text" to "fixture").apply {
                    if (canonical) put("submission_id", "fixture-submission")
                }
                val response = wire.client.nativeTurnRpc(lease, "prompt.submit", params)
                if (canonical) {
                    assertEquals("admission-1", response.getString("admission_id"))
                    assertEquals(binding.runtimeSessionId, response.getJSONObject("ref").getString("session_id"))
                } else assertEquals(1, response.getInt("user_row_id"))
                assertEquals(1, wire.connectionFrames.single().count { it.optString("method") == "prompt.submit" })
                assertEquals(1, wire.connectionMethods.size)
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun connectionLostAfterGenerationRefreshDoesNotSendSteer() = runBlocking {
        val wire = Wire().apply { canonical = true; runtime = "stored-a" }
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            wire.dropAfterSteerRefresh = true
            val error = runCatching { wire.client.nativeTurnRpc(lease, "session.steer", json("session_id" to binding.runtimeSessionId, "text" to "fixture")) }.exceptionOrNull()
            assertTrue("A lost owning connection before steer is not sent", error is NativeTurnNotSent)
            assertEquals(0, wire.methods.count { it == "session.steer" })
            assertEquals("No replacement connection is opened for a mutation", 1, wire.connectionMethods.size)
        } finally { wire.client.closeNative() }
    }

    @Test(timeout = 15_000) fun leasedRequestCannotNameAnotherRuntime() = runBlocking {
        val wire = Wire()
        try {
            val binding = wire.client.nativeSession("stored-a")
            val lease = wire.client.nativeTurnLease(binding.storedSessionId, binding.runtimeSessionId)
            val error = runCatching { wire.client.nativeTurnRpc(lease, "prompt.submit", json("session_id" to "foreign", "text" to "fixture")) }.exceptionOrNull()
            assertTrue(error is NativeTurnNotSent)
            assertEquals(0, wire.methods.count { it == "prompt.submit" })
        } finally { wire.client.closeNative() }
    }


    @Test(timeout = 10000) fun voiceSendFenceOrdersHangupAgainstTheActualSocketSend() = runBlocking {
        val denied = Wire(); val deniedClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val binding = denied.client.nativeSession("stored-a")
            assertTrue(finishCallOnce(deniedClosed) { })
            val response = denied.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                null, true, deniedClosed, { !deniedClosed.get() }, { fail("A closed call cannot claim a send"); false }, { true })
            assertEquals("not_sent", response.getString("status"))
            assertEquals("call_ended", response.getString("reason"))
            assertEquals(0, denied.methods.count { it == "prompt.submit" })
        } finally { denied.client.closeNative() }

        val wire = Wire(); val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>(); val closing = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val binding = wire.client.nativeSession("stored-a")
            val sending = async(Dispatchers.Default) {
                wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    null, true, closed, { !closed.get() }, { entered.complete(Unit); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }, { true })
            }
            withTimeout(2000) { entered.await() }
            val hangup = async(Dispatchers.Default) { closing.complete(Unit); finishCallOnce(closed) { } }
            withTimeout(2000) { closing.await() }; delay(100)
            assertFalse("Hang-up cannot overtake an in-progress guarded socket send", hangup.isCompleted)
            release.countDown()
            assertEquals("streaming", withTimeout(2000) { sending.await() }.getString("status"))
            assertTrue(withTimeout(2000) { hangup.await() })
            assertTrue(closed.get())
            assertEquals("The winning send is not repeated", 1, wire.methods.count { it == "prompt.submit" })
        } finally { release.countDown(); wire.client.closeNative() }
    }


    @Test(timeout = 10000) fun voiceStageFencePreventsUploadWhenCallEndsBeforeStageSend() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val owned = java.util.concurrent.atomic.AtomicBoolean(true)
            try {
                val binding = wire.client.nativeSession("stored-a")
                val result = wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    "fixture-jpeg", true, Any(), { owned.get() }, { fail("A closed call cannot claim prompt submission"); false },
                    { owned.set(false); true })
                assertEquals("not_sent", result.getString("status")); assertEquals("call_ended", result.getString("reason"))
                assertTrue("A closed call cannot stage or submit", wire.methods.none { it in setOf("file.attach", "image.attach_bytes", "prompt.submit") })
            } finally { wire.client.closeNative() }
        }
    }

    @Test(timeout = 10000) fun voiceStageFenceDropsRevokedFrameAndStillSubmitsText() = runBlocking {
        for (canonical in listOf(false, true)) {
            val wire = Wire().apply { this.canonical = canonical; if (canonical) runtime = "stored-a" }
            val checks = java.util.concurrent.atomic.AtomicInteger()
            try {
                val binding = wire.client.nativeSession("stored-a")
                val result = wire.client.nativeVoiceSubmit(binding, json("session_id" to binding.runtimeSessionId, "text" to "fixture"),
                    "fixture-jpeg", true, Any(), { true }, { true }, { checks.incrementAndGet() == 1 })
                assertEquals(if (canonical) "started" else "streaming", result.getString("status"))
                assertTrue(wire.methods.none { it in setOf("file.attach", "image.attach_bytes") })
                val prompt = wire.connectionFrames.single().single { it.optString("method") == "prompt.submit" }.getJSONObject("params")
                assertTrue(prompt.getString("text").contains("image could not be attached")); assertFalse(prompt.has("attachments"))
            } finally { wire.client.closeNative() }
        }
    }

}
