package dev.hermes.android.voice

import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A call start that cannot reach Hermes says so within its reach budget (calls contract section 9: the line within 4 s of the tap),
 * through the real HTTP stack: a grant nobody answers, a connection that never opens, and Hermes' own answers, which pass through.
 */
class CallStartReachTest {
    private val server = MockWebServer()
    private val clients = mutableListOf<BeaconClient>()
    @After fun close() { clients.forEach { it.closeNative() }; runCatching { server.shutdown() } }

    /** A client whose Hermes is [server] (or [port], for a port nobody listens on). */
    private fun client(port: Int = -1): BeaconClient =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                val base = if (port > 0) okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).build() else server.url("/")
                chain.proceed(original.newBuilder().url(base.newBuilder().encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()).build())
            }
        }.also { clients += it }

    private suspend fun grant(client: BeaconClient, reported: MutableList<Throwable>, reachMs: Long = GRANT_REACH_MS): JSONObject =
        reachHermes(reachMs, { reported += it }) { client.nativeRequest("/api/plugins/beacon/mobile/calls", "POST", JSONObject().put("profile", "default")) }

    @Test(timeout = 20_000) fun aGrantNobodyAnswersSaysHermesCantBeReachedWithinTheBudget() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val reported = CopyOnWriteArrayList<Throwable>()
        val started = System.nanoTime()
        val error = runCatching { grant(client(), reported) }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("$error", error is HermesUnreachable)
        assertTrue("The reach line comes at the budget, not at the 15 s limit: $elapsed ms", elapsed in GRANT_REACH_MS..GRANT_REACH_MS + 900)
        assertEquals(APP_CONNECTION_ERROR, grantFailureText(error!!))
        assertEquals(listOf(error), reported.toList())
        assertEquals("timeout", diagnosticErrorCode(error)); assertEquals("socket_timeout", voiceTransportCause(error))
        assertNotNull("The grant was sent", server.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 20_000) fun aConnectionThatNeverOpensSaysSoAtOnce() = runBlocking {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val reported = CopyOnWriteArrayList<Throwable>()
        val started = System.nanoTime()
        val error = runCatching { grant(client(closed), reported) }.exceptionOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertNotNull(error); assertFalse(error is HermesUnreachable)
        assertTrue("A refused connection does not wait for the budget: $elapsed ms", elapsed < GRANT_REACH_MS)
        assertEquals(APP_CONNECTION_ERROR, grantFailureText(error!!))
        assertEquals(listOf(error), reported.toList())
        assertEquals("connect", voiceTransportCause(generateSequence(error) { it.cause }.first { it is java.net.ConnectException }))
    }

    @Test(timeout = 20_000) fun aSlowButAnsweringGrantIsNotCutShort() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(1_200, TimeUnit.MILLISECONDS).setBody("""{"call_id":"call-synthetic","conversation_id":"stored-synthetic"}"""))
        server.start()
        val reported = CopyOnWriteArrayList<Throwable>()
        assertEquals("call-synthetic", grant(client(), reported).getString("call_id"))
        assertTrue(reported.isEmpty())
    }

    // Hermes takes a callback's one-time invitation with its answer: an answer slower than the grant's 3 s is waited for, never given up.
    @Test(timeout = 20_000) fun aCallbackAnswerSlowerThanTheGrantBudgetIsWaitedFor() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(3_500, TimeUnit.MILLISECONDS).setBody("""{"call_id":"call-synthetic","conversation_id":"stored-synthetic"}"""))
        server.start()
        val reported = CopyOnWriteArrayList<Throwable>()
        val answer = reachHermes(callStartReachMs(callbackAnswer = true), { reported += it }) {
            client().nativeRequest("/api/plugins/beacon/mobile/callbacks/event-synthetic/answer", "POST", JSONObject().put("provider", "gemini"))
        }
        assertEquals("call-synthetic", answer.getString("call_id"))
        assertTrue(reported.isEmpty())
        assertEquals("The grant keeps its 3 s budget", GRANT_REACH_MS, callStartReachMs(callbackAnswer = false))
    }

    @Test(timeout = 20_000) fun hermesOwnAnswersKeepTheirOwnLines() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Not signed in"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Hermes is updating","error_code":"hermes_updating"}"""))
        server.start()
        val reported = CopyOnWriteArrayList<Throwable>()
        val expired = runCatching { grant(client(), reported) }.exceptionOrNull()!!
        assertEquals(CALL_SIGN_IN_EXPIRED, grantFailureText(expired))
        val busy = runCatching { grant(client(), reported) }.exceptionOrNull()!!
        assertEquals(CALL_HERMES_NO_ANSWER, grantFailureText(busy))
        assertTrue("Hermes answered: it is reachable", reported.isEmpty())
    }

    @Test fun cancellingTheStartIsNotReportedAsUnreachable() = runBlocking {
        val reported = CopyOnWriteArrayList<Throwable>()
        val job = launch { reachHermes(5_000, { reported += it }) { awaitCancellation() } }
        delay(50); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(reported.isEmpty())
    }

    @Test fun theReachLineIsAFixedCallLine() {
        assertTrue("The service translates it like every other call line", APP_CONNECTION_ERROR in CallFailureStrings)
        assertEquals("hermes_unreachable", shownKey(APP_CONNECTION_ERROR))
        assertEquals(CALL_HERMES_NO_ANSWER, grantFailureText(java.net.SocketTimeoutException("timeout")))
    }
}
