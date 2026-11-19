package dev.hermes.android.agentphone

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The app's real transport over Beacon's client, against a MockWebServer standing in for L2's ticket route and view socket. */
class ViewSocketWireTest {
    private object ShownAtOnce : FrameSink {
        override fun submit(header: FrameHeader, data: ByteArray, done: (Boolean) -> Unit) = done(true)
        override fun clear() = Unit
    }

    @Test fun theTicketAndTheSocketSpeakL2sProtocol() = runBlocking<Unit> {
        val server = MockWebServer()
        val received = LinkedBlockingQueue<String>()
        val serverSide = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().setBody("""{"ticket":"tk-1_x","path":"/api/plugins/beacon/agent-phone/view","subprotocol":"beacon-agent-phone-v1","profile":"default","expires_in":20}"""))
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "beacon-agent-phone-v1").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"t":"state","keyboard":false,"rotation":0,"helper":"ready","lease":"you"}""")
                webSocket.send((byteArrayOf(1, 0, 0, 0, 9, 2, -48, 6, 64, 0) + byteArrayOf(-1, -40, -1, -39)).toByteString())
                serverSide += webSocket
            }
            override fun onMessage(webSocket: WebSocket, text: String) { received += text }
        }))
        server.start()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()).build())
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val main = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(main + SupervisorJob())
        try {
            val session = withContext(main) { ViewSession(BeaconViewTransport(client), ShownAtOnce, scope).also { it.open() } }
            withTimeout(10_000) { session.ui.first { it.phase == ViewPhase.Live && it.hasFrame } }

            val ticket = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", ticket.method)
            assertEquals("/api/plugins/beacon/mobile/agent-phone/view-ticket", ticket.requestUrl!!.encodedPath)
            assertEquals("default", ticket.requestUrl!!.queryParameter("beacon_profile"))
            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/plugins/beacon/agent-phone/view", upgrade.requestUrl!!.encodedPath)
            assertNull(upgrade.requestUrl!!.encodedQuery)
            assertEquals("beacon-agent-phone-v1, beacon-ticket.tk-1_x", upgrade.getHeader("Sec-WebSocket-Protocol"))
            assertNull(upgrade.getHeader("Authorization"))
            assertNull(upgrade.getHeader("X-Hermes-Session-Token"))

            val ack = JSONObject(received.poll(5, TimeUnit.SECONDS)!!)
            assertEquals(setOf("t", "seq"), ack.keys().asSequence().toSet())
            assertEquals("ack", ack.getString("t"))
            assertEquals(9L, ack.getLong("seq"))
            withContext(main) { session.touch(listOf(TouchPoint(0, .25, .75, "down"))) }
            val touch = JSONObject(received.poll(5, TimeUnit.SECONDS)!!)
            assertEquals("touch", touch.getString("t"))
            assertEquals(.25, touch.getJSONArray("points").getJSONObject(0).getDouble("x"), 0.0)

            withContext(main) { session.done() }
            assertEquals("""{"t":"done"}""", received.poll(5, TimeUnit.SECONDS))
            val socket = serverSide.poll(5, TimeUnit.SECONDS)!!
            socket.send("""{"t":"bye","reason":"done"}""")
            socket.close(1000, null)
            withTimeout(10_000) { session.ui.first { it.phase == ViewPhase.Closed } }
        } finally {
            scope.cancel()
            executor.shutdownNow()
            client.http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
