package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CypherClientProfileRoutingTest {
    @Test fun pluginRoutesUseCypherProfileAndNativeRoutesUseProfile() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"contract_version":2,"backend":"hermes-dashboard","owner_id":"owner","profile":"academic"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("apk"))
        server.enqueue(MockResponse().setBody("""{"profiles":[]}"""))
        server.start()
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "academic", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                val destination = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(destination).build())
            }
        }
        try {
            client.probeOptionalFeatures()
            client.nativeGet("/api/plugins/cypher/admin/model-capabilities")
            client.nativeRequest("/api/plugins/cypher/mobile/projects", "POST", JSONObject())
            client.nativeGetForProfile(
                "/api/plugins/cypher/mobile/notifications/events", "coder", listOf("coder"),
                mapOf("after" to "0", "profile" to "academic", "cypher_profile" to "default"),
            )
            assertEquals(3L, client.copyTo(
                "/api/plugins/cypher/mobile/releases/current/download", ByteArrayOutputStream(),
                query = mapOf("profile" to "academic", "cypher_profile" to "foreign", "session_id" to "ignored"), limit = 16,
            ))
            client.nativeGetForProfile(
                "/api/profiles", "coder", listOf("coder"), mapOf("page" to "1", "cypher_profile" to "academic"),
            )

            val requests = List(6) { server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing request $it") }
            assertPluginProfile(requests[0], "academic")
            assertPluginProfile(requests[1], "academic")
            assertPluginProfile(requests[2], "academic")
            assertPluginProfile(requests[3], "coder")
            assertPluginProfile(requests[4], "academic")
            assertEquals("GET", requests[0].method)
            assertEquals("POST", requests[2].method)
            assertEquals("/api/plugins/cypher/mobile/notifications/events", requests[3].requestUrl!!.encodedPath)
            assertEquals("/api/plugins/cypher/mobile/releases/current/download", requests[4].requestUrl!!.encodedPath)
            assertNull(requests[4].requestUrl!!.queryParameter("session_id"))
            assertNativeProfile(requests[5], "coder")
        } finally {
            client.closeNative()
            client.http.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private fun assertPluginProfile(request: RecordedRequest, expected: String) {
        assertEquals(expected, request.requestUrl!!.queryParameter("cypher_profile"))
        assertNull(request.requestUrl!!.queryParameter("profile"))
    }

    private fun assertNativeProfile(request: RecordedRequest, expected: String) {
        assertEquals(expected, request.requestUrl!!.queryParameter("profile"))
        assertNull(request.requestUrl!!.queryParameter("cypher_profile"))
    }
}
