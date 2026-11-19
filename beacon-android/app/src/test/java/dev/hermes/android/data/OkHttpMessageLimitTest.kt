package dev.hermes.android.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Why a large file goes to the plugin in pieces (CHAT-121 follow-up 4): OkHttp, the phone's socket client, refuses to queue a message that takes its
 * queue past 16 MiB (okhttp3.internal.ws.RealWebSocket.MAX_QUEUE_SIZE) and closes the socket instead, for a text or a binary message alike. A
 * 30 MB file is about 40 MB of base64, so it can never be one message; a 4 MiB piece goes.
 */
class OkHttpMessageLimitTest {
    private fun exchange(send: (WebSocket) -> Boolean): Pair<Boolean, Boolean> {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        val client = OkHttpClient()
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)
        try {
            val socket = client.newWebSocket(Request.Builder().url(server.url("/")).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = ended.countDown()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended.countDown()
            })
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            val accepted = send(socket)
            val closed = ended.await(if (accepted) 1 else 10, TimeUnit.SECONDS)
            socket.cancel()
            return accepted to closed
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            // The server's side of a socket the client cancelled can outlive shutdown's wait; that says nothing about the client's limit.
            runCatching { server.shutdown() }
        }
    }

    @Test fun aMessageOver16MiBIsRefusedAndTheSocketClosesWhileAPieceGoes() {
        val limit = 16 * 1024 * 1024
        val (binary, binaryClosed) = exchange { it.send(ByteArray(limit + 1).toByteString()) }
        assertFalse("16 MiB + 1 as bytes is refused", binary)
        assertTrue("and OkHttp closes the socket", binaryClosed)
        val (text, textClosed) = exchange { it.send("A".repeat(limit + 1)) }
        assertFalse("16 MiB + 1 as text is refused", text)
        assertTrue(textClosed)
        val (piece, pieceClosed) = exchange { it.send(ByteArray(4 * 1024 * 1024).toByteString()) }
        assertTrue("a 4 MiB piece is queued", piece)
        assertFalse("and the socket stays open", pieceClosed)
    }
}
