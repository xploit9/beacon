package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CypherClientAuthTest {
    private fun unauthorizedClient(auth: AuthContext, requests: MutableList<Request>, body: String) = CypherClient(auth, null) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Test fun loopbackPluginUnauthorizedNeverEndsSignInAndKeepsTheSession() = runBlocking {
        val origin = "https://native.example"
        val loopback = LoopbackSessionRecord("loopback-token", origin, "local", verified = true)
        val requests = mutableListOf<Request>()
        val client = unauthorizedClient(
            AuthContext(origin, emptyList(), "local", backend = "hermes-dashboard", loopbackSession = loopback),
            requests,
            """{"detail":"Verified native session required"}""",
        )

        val error = runCatching { client.nativeRequest("/api/plugins/cypher/mobile/bootstrap") }.exceptionOrNull()

        assertTrue(error is ApiFailure)
        assertEquals(401, (error as ApiFailure).status)
        assertTrue(error.plugin)
        assertTrue(!dev.hermes.android.endsSignIn(error))
        assertEquals("loopback-token", requests.single().header(HERMES_LOOPBACK_SESSION_HEADER))
        assertEquals(loopback, client.auth.loopbackSession)
        client.closeNative()
    }

    @Test fun nativeUnauthorizedRemains401() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = unauthorizedClient(AuthContext("https://native.example", emptyList()), requests, """{"detail":"Unauthorized"}""")

        val error = runCatching { client.nativeRequest("/api/auth/me") }.exceptionOrNull()

        assertTrue(error is ApiFailure)
        assertEquals(401, (error as ApiFailure).status)
        assertTrue(dev.hermes.android.endsSignIn(error))
        assertEquals("/api/auth/me", requests.single().url.encodedPath)
        client.closeNative()
    }
}
