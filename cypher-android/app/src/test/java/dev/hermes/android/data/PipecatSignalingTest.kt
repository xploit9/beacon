package dev.hermes.android.data
import org.junit.Assert.*
import org.junit.Test
class PipecatSignalingTest {
    @Test fun archivedWebRtcSignalingIsUnavailable() {
        val client = CypherClient(AuthContext("https://hermes.example", emptyList()))
        assertTrue(runCatching { client.request("/api/mobile/xara/calls/call-1/webrtc") }.isFailure)
        assertTrue(runCatching { client.request("//foreign/api/auth/me") }.isFailure)
        assertNull(client.request("/api/auth/ws-ticket").build().header("X-Hermes-CSRF-Token"))
    }
}
