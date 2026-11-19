package dev.hermes.android

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.progressClockMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REVIEW-F7 P3-2 (fix 8): a call press's start-over supersedes the attempt in flight. If that attempt's /api/auth/me had rotated the refresh
 * token (the answer's headers came, its body stalled), the replacement read the stored, spent token: a provider with reuse detection signs the
 * owner out. Now the start-over keeps that rotation first, checked against the account as the attempt's own failure path checks it.
 * Also P3-3's progress clock: a client marks Hermes reaching its own connections.
 */
class BeaconSupersededSignInTest {
    private val origin = "https://native.example"
    private val spent = "__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"
    private fun account(owner: String = "alice") = AuthContext(origin, listOf(spent), owner, backend = "hermes-dashboard", nativeProvider = "basic")
    private fun rotate(client: BeaconClient) =
        client.jar.saveFromResponse(origin.toHttpUrl(), listOf(requireNotNull(Cookie.parse(origin.toHttpUrl(), "__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly"))))

    @Test fun aStartOverKeepsTheSupersededAttemptsRotationForItsReplacement() = runBlocking {
        val attempt = BeaconClient(account(), null, persistContext = false)
        try {
            rotate(attempt)
            var persisted: AuthContext? = null
            assertTrue(keepSupersededRotation(attempt, account().scope()) { persisted = it })
            assertTrue("the replacement reads the rotated token: $persisted", persisted?.cookies?.any { it.contains("=after") } == true)
            assertFalse("never the spent one", persisted?.cookies?.any { it.contains("=before") } == true)
        } finally { attempt.closeNative() }
    }

    @Test fun anotherAccountsRotationIsNeverKept() = runBlocking {
        val attempt = BeaconClient(account("mallory"), null, persistContext = false)
        try {
            rotate(attempt)
            var persisted: AuthContext? = null
            assertFalse(keepSupersededRotation(attempt, account("alice").scope()) { persisted = it })
            assertNull(persisted)
            // No account to check against (a fresh sign-in): nothing kept, as the attempt's own failure path keeps nothing then.
            assertFalse(keepSupersededRotation(attempt, null) { persisted = it })
            assertNull(persisted)
        } finally { attempt.closeNative() }
    }

    @Test fun aClientMarksHermesReachingItsOwnConnection() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("""{"ok":true}""")) }
        // Its requests go to the fixture server (as BeaconClientAuthTest routes them); the network interceptor sees the real connection.
        val client = BeaconClient(AuthContext(origin, emptyList()), null, persistContext = false) { builder ->
            builder.addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
        }
        try {
            assertEquals(0L, client.hermesProgressAt)
            val before = progressClockMs()
            runCatching { client.get("/api/status") }
            assertTrue("a connection reached Hermes and its answer came: ${client.hermesProgressAt} >= $before", client.hermesProgressAt >= before)
        } finally { client.closeNative(); server.shutdown() }
    }

    @Test fun aRequestOnAPooledConnectionShowsNoProgressUntilItsAnswerComes() = runBlocking {
        // A pooled socket may have died with the network: the request going on it is no sign of Hermes. A new connection is.
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"ok":true}"""))
            enqueue(MockResponse().setBody("""{"ok":true}""").setHeadersDelay(1_200, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        val client = BeaconClient(AuthContext(origin, emptyList()), null, persistContext = false) { builder ->
            builder.addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
        }
        try {
            runCatching { client.get("/api/status") }
            val first = client.hermesProgressAt
            assertTrue(first > 0L)
            val second = async(Dispatchers.IO) { runCatching { client.get("/api/status") } }
            delay(600)
            assertEquals("the pooled connection's request alone shows nothing", first, client.hermesProgressAt)
            second.await()
            server.takeRequest()
            assertEquals("one connection, reused", 1, server.takeRequest().sequenceNumber)
            assertTrue("its answer does: ${client.hermesProgressAt} > $first", client.hermesProgressAt >= first + 1_000)
        } finally { client.closeNative(); server.shutdown() }
    }

    @Test fun aNewConnectionReachingHermesIsProgressBeforeItsAnswerComes() = runBlocking {
        // REVIEW-F8 P3-2: the half of the signal F7 P3-3 rests on. Hermes holds its answer 1.5 s; the new connection reached it, so the client
        // already shows progress while the answer is held. Without this mark a connect Hermes serves slowly would be cut at 3 s again.
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"ok":true}""").setHeadersDelay(1_500, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        val client = BeaconClient(AuthContext(origin, emptyList()), null, persistContext = false) { builder ->
            builder.addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
        }
        try {
            val before = progressClockMs()
            val read = async(Dispatchers.IO) { runCatching { client.get("/api/status") } }
            delay(500)
            val marked = client.hermesProgressAt
            assertTrue("the new connection marks progress while its answer is held: $marked >= $before", marked >= before)
            read.await()
            assertTrue("then its answer marks again: ${client.hermesProgressAt} >= $marked + 1000", client.hermesProgressAt >= marked + 1_000)
        } finally { client.closeNative(); server.shutdown() }
    }
}
