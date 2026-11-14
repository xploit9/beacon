package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BeaconClientRetryTest {
    private val canary = "synthetic-retry-canary"
    private val auth = AuthContext("https://retry-fixture.invalid", listOf("hermes_session=fixture; Path=/; Secure"), "owner", "default", backend = "hermes-dashboard", nativeProvider = "basic")
    private fun response(request: okhttp3.Request, status: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun onlyReadsRetryOneBrokenSocketAndMutationsKeepTheirUnknownOutcome() = runBlocking {
        for (method in listOf("GET", "POST")) {
            val count = AtomicInteger()
            val client = BeaconClient(auth, null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    if (count.incrementAndGet() == 1) throw java.net.SocketException(canary)
                    response(chain.request(), 200, "{}")
                }
            }
            try {
                val result = runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health", method, if (method == "POST") JSONObject() else null) }
                assertEquals(method == "GET", result.isSuccess)
                assertEquals(if (method == "GET") 2 else 1, count.get())
            } finally { client.closeNative() }
        }
    }
}
