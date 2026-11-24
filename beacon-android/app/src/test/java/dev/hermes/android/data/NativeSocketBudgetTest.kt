package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1 (1.2.3): the dead Send. The plugin admits MAX_SOCKETS (8) chat sockets at once and refuses the next at once (canonical_transport.socket_route:
 * close 1008 before accept, a 403 upgrade). A detached client nobody closed kept its socket open for the life of the process, so 8 of them made every
 * later send fail until a force-stop (R2C, Q2's Hermes, 22 Nov: 8 sockets open 17:15-17:34, "Not sent" in about 6 s, Settings said Connected).
 */
class NativeSocketBudgetTest {
    /** The plugin's socket rule: [cap] chat sockets open at once, the next refused before it opens. */
    private class Plugin(val origin: String, val cap: Int = 8) : WebSocket.Factory {
        val open: MutableSet<WebSocket> = java.util.Collections.synchronizedSet(mutableSetOf())
        val accepted = AtomicInteger()
        val refused = AtomicInteger()
        val ended = AtomicInteger()
        val client = own("default")
        /** An app-own client (not detached), as the view model's after a sign-in or a profile switch. */
        fun own(profile: String) = BeaconClient(AuthContext(origin, emptyList(), "owner", profile, backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.encodedPath == CANONICAL_TICKET_PATH) json("ticket" to "fixture-ticket", "path" to CANONICAL_SOCKET_PATH,
                    "profile" to "default", "canonical" to true, "expires_in" to 25) else json("ticket" to "fixture-ticket")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                fun end() { if (open.remove(this)) ended.incrementAndGet() }
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = end()
                override fun close(code: Int, reason: String?): Boolean { end(); return true }
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    if (this !in open) return false
                    val frame = JSONObject(text)
                    if (!frame.has("id") || !frame.has("method")) return true
                    val result = if (frame.getString("method") == "session.active_list") json("sessions" to JSONArray()) else JSONObject()
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString())
                    return true
                }
            }
            if (open.size >= cap) {
                refused.incrementAndGet()
                listener.onFailure(socket, java.net.ProtocolException("Expected HTTP 101 response but was '403'"),
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("Refused").build())
                return socket
            }
            open += socket
            accepted.incrementAndGet()
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to true))).toString())
            return socket
        }
    }

    /** Runs [block] with the idle clock [ahead] ms ahead of real time once [move] is called. */
    private inline fun <T> withClock(block: (move: (Long) -> Unit) -> T): T {
        val real = idleSocketNowMs
        var offset = 0L
        idleSocketNowMs = { real() + offset }
        try { return block { offset += it } } finally { idleSocketNowMs = real }
    }

    private suspend fun probe(plugin: Plugin): BeaconClient = plugin.client.detached().also { it.nativeRpc("session.active_list", json()) }

    @Test(timeout = 20_000) fun eightSocketsNobodyClosedNoLongerKillEverySendTheNextOneGetsInInTheSameProcess() = runBlocking {
        val plugin = Plugin("https://s1-dead-send.example")
        withClock { move ->
            val leaked = (1..8).map { probe(plugin) }  // dropped without closeNative, as a probe on a path that skipped it
            assertEquals("the plugin is full", 8, plugin.open.size)
            move(NATIVE_SOCKET_REFUSED_IDLE_MS + 1_000)
            val send = plugin.client.detached()
            try {
                send.nativeRpc("session.active_list", json())
                assertTrue("the plugin refused it once, then let it in", plugin.refused.get() >= 1)
                assertTrue("this app's idle sockets were ended to make room", plugin.ended.get() >= 1)
                assertTrue(plugin.open.size <= plugin.cap)
            } finally { send.closeNative(); leaked.forEach { it.closeNative() } }
        }
    }

    @Test(timeout = 20_000) fun aNewSocketEndsThisAppsIdleOnesBeyondItsBudgetBeforeThePluginIsFull() = runBlocking {
        val plugin = Plugin("https://s1-budget.example")
        withClock { move ->
            plugin.client.nativeRpc("session.active_list", json())  // the app's own client: never ended for being idle
            val leaked = (1 until NATIVE_SOCKET_BUDGET).map { probe(plugin) }
            assertEquals(NATIVE_SOCKET_BUDGET, plugin.open.size)
            move(NATIVE_SOCKET_IDLE_MS + 1_000)
            val next = probe(plugin)
            try {
                assertEquals("no refusal: the budget is under the plugin's cap", 0, plugin.refused.get())
                assertEquals("one idle socket of this app was ended for the new one", NATIVE_SOCKET_BUDGET, plugin.open.size)
                assertEquals(NATIVE_SOCKET_BUDGET, NativeGateway.heldSockets("https://s1-budget.example"))
                // The own client's socket is still the one it opened: it answers without a new admission.
                val before = plugin.accepted.get()
                plugin.client.nativeRpc("session.active_list", json())
                assertEquals("the app's own socket was kept", before, plugin.accepted.get())
            } finally { next.closeNative(); leaked.forEach { it.closeNative() }; plugin.client.closeNative() }
        }
    }

    @Test(timeout = 20_000) fun aSocketThatIsReadingEventsIsNeverEnded() = runBlocking {
        val plugin = Plugin("https://s1-reader.example")
        withClock { move ->
            val stream = plugin.client.detached()
            val reader = launch { stream.nativeEvents { } }
            withTimeout(5_000) { while (plugin.open.isEmpty()) kotlinx.coroutines.delay(10) }
            val streamSocket = plugin.open.single()
            val leaked = (1..7).map { probe(plugin) }
            move(NATIVE_SOCKET_IDLE_MS + 1_000)
            val send = probe(plugin)
            try {
                assertTrue("the stream's socket is still open", streamSocket in plugin.open)
                assertTrue(plugin.open.size <= plugin.cap)
            } finally { reader.cancel(); stream.closeNative(); send.closeNative(); leaked.forEach { it.closeNative() } }
        }
    }

    /**
     * S1d (R3 review #4): a profile switch or a reconnect replaced the view model's own client without closing it. An own client is never ended
     * for being idle, so each replaced one kept a socket for good, and enough of them made every send fail again. Closing the replaced one (the
     * client setter now does) leaves only the current client's socket.
     */
    @Test(timeout = 20_000) fun ownClientsReplacedWithoutClosingFillThePluginClosingEachReplacedOneLeavesOnlyTheCurrent() = runBlocking {
        val plugin = Plugin("https://s1d-switch.example")
        withClock { move ->
            val replaced = (1..8).map { plugin.own("default").also { c -> c.nativeRpc("session.active_list", json()) } }  // one own client per switch
            move(NATIVE_SOCKET_REFUSED_IDLE_MS + NATIVE_SOCKET_IDLE_MS)
            val send = plugin.client.detached()
            try {
                val refused = runCatching { send.nativeRpc("session.active_list", json()) }.exceptionOrNull()
                assertNotNull("eight stranded own sockets: the budget cannot end them and the send is refused", refused)
                assertEquals(8, plugin.open.size)
                replaced.dropLast(1).forEach { it.closeNative() }
                assertEquals("only the current client's socket is left", 1, plugin.open.size)
                assertEquals(1, NativeGateway.heldSockets(plugin.origin))
                send.nativeRpc("session.active_list", json())
                assertEquals(2, plugin.open.size)
            } finally { send.closeNative(); replaced.forEach { it.closeNative() }; plugin.client.closeNative() }
        }
    }

    /** Goal 1's count at the socket seam: each probe that closes opens one socket and ends it; nothing is left held. */
    @Test(timeout = 20_000) fun probesThatCloseOpenOneSocketEachAndLeaveNoneHeld() = runBlocking {
        val plugin = Plugin("https://s1-count.example")
        repeat(5) { probe(plugin).closeNative() }
        assertEquals(5, plugin.accepted.get())
        assertEquals(5, plugin.ended.get())
        assertEquals(0, NativeGateway.heldSockets("https://s1-count.example"))
    }
}
