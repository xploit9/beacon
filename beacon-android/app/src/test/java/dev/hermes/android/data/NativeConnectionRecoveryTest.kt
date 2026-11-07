package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class NativeConnectionRecoveryTest {
    @Test fun toolDiscoveryRecoversFromOneDnsFailure() = runBlocking {
        val attempts = AtomicInteger()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "alice"), null) { builder ->
            builder.addInterceptor { chain ->
                if (attempts.incrementAndGet() == 1) throw UnknownHostException("Synthetic DNS interruption")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("""{"tools":[],"total_matches":0}""".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertEquals(0, NativeToolGateway(client, "call").discover("browser").getInt("total_matches"))
            assertEquals(2, attempts.get())
            assertFalse(HermesReach.origins.value.contains(client.auth.origin))
        } finally { client.closeNative(); HermesReach.answered(client.auth.origin) }
    }

    @Test fun persistentDnsFailureIsBoundedAndMutationsAreNeverReplayed() = runBlocking {
        for (method in listOf("GET", "POST")) {
            val attempts = AtomicInteger()
            val client = BeaconClient(AuthContext("https://native.example", emptyList(), "alice"), null) { builder ->
                builder.addInterceptor { attempts.incrementAndGet(); throw UnknownHostException("Synthetic DNS interruption") }
            }
            try {
                val result = runCatching { client.nativeRequest("/api/plugins/beacon/mobile/calls", method, if (method == "POST") JSONObject() else null) }
                assertTrue(result.exceptionOrNull() is UnknownHostException)
                assertEquals(method, if (method == "GET") 2 else 1, attempts.get())
            } finally { client.closeNative(); HermesReach.answered(client.auth.origin) }
        }
    }

    @Test fun dnsRetryCannotCrossAProfileChange() = runBlocking {
        val attempts = AtomicInteger()
        lateinit var client: BeaconClient
        client = BeaconClient(AuthContext("https://native.example", emptyList(), "alice"), null) { builder ->
            builder.addInterceptor {
                attempts.incrementAndGet()
                runBlocking { client.bindProfile("work") }
                throw UnknownHostException("Synthetic DNS interruption")
            }
        }
        try {
            assertTrue(runCatching { NativeToolGateway(client, "call").discover("browser") }.exceptionOrNull() is UnknownHostException)
            assertEquals(1, attempts.get())
        } finally { client.closeNative(); HermesReach.answered(client.auth.origin) }
    }

    @Test fun certificateRefusalsAreNeverRetried() = runBlocking {
        val attempts = AtomicInteger()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "alice"), null) { builder ->
            builder.addInterceptor { attempts.incrementAndGet(); throw javax.net.ssl.SSLPeerUnverifiedException("Synthetic certificate refusal") }
        }
        try {
            assertTrue(runCatching { NativeToolGateway(client, "call").discover("browser") }.exceptionOrNull() is javax.net.ssl.SSLPeerUnverifiedException)
            assertEquals(1, attempts.get())
        } finally { client.closeNative() }
    }
}
