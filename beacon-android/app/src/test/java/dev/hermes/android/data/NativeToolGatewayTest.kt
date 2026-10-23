package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeToolGatewayTest {
    @Test fun receiptIdentityRejectsPathAndQueryInjection() {
        listOf("", "../other", "a/b", "a?profile=other", "a%2fb", "a\\b", "x".repeat(161)).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeToolGateway.requireRequestId(it) }
        }
        NativeToolGateway.requireRequestId("gemini_call-123_abc")
    }

    @Test fun decisionSendsOnlyTheExactApprovedWireValue() = runBlocking {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null,
            configureHttp = { builder -> builder.addInterceptor { chain ->
                val request = chain.request()
                val body = Buffer().also { request.body!!.writeTo(it) }
                sent += request.url.encodedPath to JSONObject(body.readUtf8())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            } })
        try {
            val gateway = NativeToolGateway(client, "call-1")
            for (decision in listOf("deny", "approve", "approve_call")) gateway.decide("request-1", "p".repeat(32), decision)

            assertEquals(listOf("deny", "approve", "approve_call"), sent.map { it.second.getString("decision") })
            assertTrue(sent.all { it.first.endsWith("/requests/request-1/decision") })
            assertTrue(sent.all { it.second.keys().asSequence().toSet() == setOf("proposal_id", "decision") })
            assertTrue(sent.all { it.second.getString("proposal_id") == "p".repeat(32) })
            assertTrue(runCatching { gateway.decide("request-1", "p".repeat(32), "approve_all") }.isFailure)
        } finally { client.closeNative() }
    }
}
