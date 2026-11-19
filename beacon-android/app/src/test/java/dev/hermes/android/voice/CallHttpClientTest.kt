package dev.hermes.android.voice

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The call's own Hermes client (calls contract v3, section 6) on a real HTTP/2 connection: pings every 5 s, a dead connection fails
 * the request on it within about 10 s (before the request's own 12 s limit) and is never used again, a slow answer on a healthy
 * connection is not a failure, and neither is a phone or path that stops for a few seconds and then goes on (LINK-121).
 * A relay between the client and MockWebServer stalls chosen connections, as the owner's path did (CALLS-A-REPORT, part 1).
 */
class CallHttpClientTest {
    /**
     * A TCP relay; [hold] stops every connection open at that moment from passing bytes either way, and loses them. New connections
     * pass. [pause] delays every byte either way for a while and then delivers it: a phone that did not run, or a slow path.
     */
    private class Relay(private val target: Int) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        @Volatile private var pausedUntil = 0L
        private val held = CopyOnWriteArrayList<Socket>()
        private val open = CopyOnWriteArrayList<Socket>()
        val connections = AtomicInteger()
        val port get() = server.localPort
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    connections.incrementAndGet()
                    val upstream = Socket(InetAddress.getLoopbackAddress(), target)
                    open += client
                    pipe(client, upstream);pipe(upstream, client)
                }
            }
        }
        private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
            val buffer = ByteArray(16384)
            try {
                while (true) {
                    val n = from.getInputStream().read(buffer)
                    if (n < 0) break
                    // A held connection swallows what it reads: a ping goes out and no pong ever comes back.
                    if (held.contains(from) || held.contains(to)) continue
                    val wait = pausedUntil - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    to.getOutputStream().write(buffer, 0, n);to.getOutputStream().flush()
                }
            } catch (_: Exception) { }
            runCatching { to.close() };runCatching { from.close() }
        }
        fun hold() { held += open }
        fun pause(millis: Long) { pausedUntil = System.nanoTime() + millis * 1_000_000 }
        override fun close() { server.close();open.forEach { runCatching { it.close() } } }
    }

    private fun h2Server() = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE);start() }
    private fun client(port: Int) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
                chain.proceed(original.newBuilder().url(url).build())
            }
        }
    private val path = "/api/plugins/beacon/mobile/calls/call"
    private fun answer() = MockResponse().setBody("""{"jobs":[]}""").setHeader("Content-Type", "application/json")

    @Test fun theCallsClientPingsOnConnectionsOfItsOwnAndSaysItsContract() = runBlocking {
        val server = h2Server()
        val source = client(server.port)
        try {
            val calls = source.forCall()
            assertEquals(5000, calls.http.pingIntervalMillis)
            assertNotSame("A stalled call connection must never be shared with the app's other requests", source.http.connectionPool, calls.http.connectionPool)
            assertEquals("The app's own client is unchanged", 0, source.http.pingIntervalMillis)
            assertEquals(source.scope, calls.scope)
            server.enqueue(answer())
            calls.nativeRequest(path)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("3", recorded.getHeader("X-Beacon-Call-Contract"))
        } finally { source.closeNative();server.shutdown() }
    }

    @Test(timeout = 30000) fun aStalledConnectionFailsItsRequestBeforeItsOwnTimeoutAndTheNextRequestOpensANewOne() = runBlocking {
        val server = h2Server()
        val relay = Relay(server.port)
        val source = client(relay.port)
        try {
            val calls = source.forCall()
            val told = CopyOnWriteArrayList<JSONObject>()
            val link = CallLinkState({ System.nanoTime() / 1_000_000 }, { replies, _ -> replies.forEach { told += it.result } })
            link.open("search", "notes_search", "req-1", hermes = true, retrySafe = true)
            server.enqueue(answer())
            link.track("search") { calls.nativeRequest(path) }
            assertEquals(1, relay.connections.get())
            // The path stalls with the request in flight: Hermes would answer, but no byte gets through either way.
            relay.hold()
            server.enqueue(answer().setHeadersDelay(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val stalled = runCatching { link.track("search", CALL_INVOKE_WAIT_MS) { calls.nativeRequest(path) } }.exceptionOrNull()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("CALL_STALL_DETECTED_MS=$elapsed ${stalled?.javaClass?.simpleName}")
            assertTrue("The stalled request failed as I/O: $stalled", stalled is java.io.IOException)
            // A ping goes within 5 s of the stall and fails 5 s later: about 10 s, before the request's own 12 s limit (8 s wait + 4 s).
            assertTrue("A failed ping is noticed within about 10 s, before the 12 s request timeout: $elapsed ms", elapsed < CALL_INVOKE_WAIT_MS + CALL_REQUEST_MARGIN_MS)
            assertEquals(Link.LOST, link.link)
            assertEquals("The model hears it once, as a state", listOf("hermes_unreachable"), told.map { it.getString("status") })
            assertEquals("connection_lost", told.single().getString("detail"))
            // The dead connection is gone: the next request opens a new one, which works.
            server.enqueue(answer())
            assertEquals(0, link.track("search") { calls.nativeRequest(path) }.getJSONArray("jobs").length())
            assertEquals(2, relay.connections.get())
            assertEquals(Link.OK, link.link)
            assertEquals("Only the new, healthy connection is pooled", 1, calls.http.connectionPool.connectionCount())
        } finally { source.closeNative();relay.close();server.shutdown() }
    }

    @Test(timeout = 20000) fun aSlowAnswerOnAHealthyConnectionIsNeverMistakenForALostLink() = runBlocking {
        val server = h2Server()
        val relay = Relay(server.port)
        val source = client(relay.port)
        try {
            val calls = source.forCall()
            val link = CallLinkState({ System.nanoTime() / 1_000_000 }, { _, _ -> })
            server.enqueue(answer())
            link.track(null) { calls.nativeRequest(path) }
            // Hermes takes 5 s (a tool waiting for its result); pings keep being answered meanwhile.
            server.enqueue(answer().setHeadersDelay(5, TimeUnit.SECONDS))
            val value = link.track(null, CALL_INVOKE_WAIT_MS) { calls.nativeRequest(path) }
            assertEquals(0, value.getJSONArray("jobs").length())
            assertEquals(Link.OK, link.link)
            assertEquals("Still the first connection", 1, relay.connections.get())
        } finally { source.closeNative();relay.close();server.shutdown() }
    }

    /**
     * Device round 3 (LINK-121): a starved phone stopped running for up to 2.6 s, and a pong that came back in 1 ms was taken for a dead
     * link because the next 1.5 s ping came first; the hand-over in flight on that connection failed. Everything here stops for 3.2 s
     * (a little longer than the worst stall seen) and then arrives: the healthy link keeps its request and its one connection.
     */
    @Test(timeout = 30000) fun aPhoneOrPathThatStopsForAFewSecondsKeepsItsHealthyLink() = runBlocking {
        val server = h2Server()
        val relay = Relay(server.port)
        val source = client(relay.port)
        try {
            val calls = source.forCall()
            val told = CopyOnWriteArrayList<JSONObject>()
            val link = CallLinkState({ System.nanoTime() / 1_000_000 }, { replies, _ -> replies.forEach { told += it.result } })
            link.open("handover", "hermes_work", "req-1", hermes = true, retrySafe = true)
            server.enqueue(answer())
            link.track(null) { calls.nativeRequest(path) }
            // The stall starts 4 s into the connection, so a ping falls inside it whatever the interval (1.5 s before, 5 s now) and its
            // pong comes 3.2 s late; earlier pings and pongs passed, so the connection was fully healthy when it began.
            Thread.sleep(4_000)
            relay.pause(3_200)
            server.enqueue(answer().setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val value = link.track("handover", CALL_HANDOVER_WAIT_MS) { calls.nativeRequest(path) }
            assertEquals(0, value.getJSONArray("jobs").length())
            assertEquals(Link.OK, link.link)
            assertTrue("The model was told nothing about a link that never went: $told", told.isEmpty())
            assertEquals("Still the first connection", 1, relay.connections.get())
        } finally { source.closeNative();relay.close();server.shutdown() }
    }
}
