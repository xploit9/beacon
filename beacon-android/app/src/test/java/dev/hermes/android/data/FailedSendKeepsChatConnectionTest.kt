package dev.hermes.android.data

import dev.hermes.android.LINK_RETRY_PAUSES_MS
import dev.hermes.android.core.json
import dev.hermes.android.linkCouldNotCarry
import dev.hermes.android.nextLinkTryPause
import kotlinx.coroutines.runBlocking
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
 * CHAT-121 follow-up 11 (backlog 82). Follow-up 10's cut, window 4: a send that failed on the chat's own client closed the chat's socket
 * (NativeOperations.call closed its client after every call), so the chat had no socket for 7 s. A failed send now leaves the chat's own connection as it
 * is; a detached client, whose socket is the send's alone, still closes it. And the pure rules of a send the link could not carry.
 */
class FailedSendKeepsChatConnectionTest {
    private class Hermes : WebSocket.Factory {
        val sockets = AtomicInteger()
        val prompts = AtomicInteger()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, this) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                val ticket = path == CANONICAL_TICKET_PATH
                val body = if (ticket) json("detail" to "Not Found") else json("ticket" to "fixture-ticket")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (ticket) 404 else 200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            sockets.incrementAndGet()
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    val id = frame.getString("id")
                    val params = frame.getJSONObject("params")
                    if (frame.getString("method") == "prompt.submit") {
                        prompts.incrementAndGet()
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to id, "error" to json("code" to 4009, "message" to "Fixture refused the prompt")).toString())
                        return true
                    }
                    val result = when (frame.getString("method")) {
                        "session.resume" -> json("session_id" to "runtime-a", "session_key" to "stored-a", "message_count" to 0, "messages" to JSONArray(), "info" to json("model" to "fixture-model"))
                        "session.events.since" -> json("epoch" to "fixture-epoch", "latest_seq" to 0, "events" to JSONArray())
                        "session.active_list" -> json("sessions" to JSONArray().put(json("id" to "runtime-a", "session_key" to "stored-a", "status" to "idle")))
                        "session.list" -> json("sessions" to JSONArray())
                        else -> JSONObject()
                    }
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to id, "result" to result).toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json())).toString())
            return socket
        }
    }

    @Test(timeout = 15_000) fun aSendThatFailsOnTheChatsOwnClientLeavesItsConnectionOpenAndADetachedOneClosesItsOwn() = runBlocking {
        for (own in listOf(true, false)) {
            val hermes = Hermes()
            val sender = if (own) hermes.client else hermes.client.detached()
            try {
                val failed = runCatching { sender.post("/api/chat/start", json("session_id" to "stored-a", "message" to "Synthetic prompt", "client_request_id" to "submission-a")) }.exceptionOrNull()
                assertNotNull("The send failed", failed)
                assertEquals(1, hermes.prompts.get())
                assertEquals(1, hermes.sockets.get())
                // The next request of the same client: on the chat's own client it rides the connection the failed send left open.
                sender.nativeRpc("session.list", json("limit" to 1))
                assertEquals(if (own) "the chat's connection stayed open" else "the detached send closed its own socket", if (own) 1 else 2, hermes.sockets.get())
            } finally { sender.closeNative(); hermes.client.closeNative() }
        }
    }

    @Test fun onlyAFailureOfTheLinkWhileHermesAnswersIsASendTheLinkCouldNotCarry() {
        val timedOut = NativeTurnNotSent("Couldn't open the chat. Your message is kept.", java.io.IOException("Native connection admission timed out"))
        val dropped = NativeTurnNotSent("Native connection changed before the request")
        val refusedByHermes = NativeTurnNotSent("Couldn't open the chat. Your message is kept.", ApiFailure(4001, "session not found"))
        val noCause = NativeTurnNotSent("Couldn't open the chat. Your message is kept.")
        assertTrue(linkCouldNotCarry(timedOut))
        assertTrue(linkCouldNotCarry(dropped))
        // Follow-up 11's first live block: the ticket's request waited behind the queue and came back expired by the phone's clock.
        assertTrue(linkCouldNotCarry(NativeTurnNotSent("Couldn't open the chat. Your message is kept.", IllegalStateException("Canonical gateway ticket expired"))))
        assertFalse("another identity check is not the link", linkCouldNotCarry(NativeTurnNotSent("Couldn't open the chat. Your message is kept.", IllegalStateException("Native identity changed during ticket admission"))))
        assertFalse("Hermes' own refusal", linkCouldNotCarry(refusedByHermes))
        assertFalse("nothing says it was the link", linkCouldNotCarry(noCause))
        assertFalse("a file that could not get through has its own rule", linkCouldNotCarry(AttachmentStalled(java.io.IOException("lost"))))
        assertFalse("a refusal with its own words", linkCouldNotCarry(NativeTurnNotSent("The model changed. Pick this message's model to send it.")))
        assertFalse("not a send that never left", linkCouldNotCarry(java.io.IOException("Native connection admission timed out")))
    }

    @Test fun aSendTheLinkCouldNotCarryGoesAgainTwiceThenWaitsForSend() {
        assertEquals(3, ATTACHMENT_STALL_TRIES)
        assertEquals(LINK_RETRY_PAUSES_MS[0], nextLinkTryPause(1))
        assertEquals(LINK_RETRY_PAUSES_MS[1], nextLinkTryPause(2))
        assertNull("three tries in all, as a file's", nextLinkTryPause(3))
        assertNull(nextLinkTryPause(4))
    }
}
