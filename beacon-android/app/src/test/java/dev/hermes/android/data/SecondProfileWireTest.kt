package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * What the app does when Hermes refuses the Beacon plugin for a profile (`web_server_dashboard.py` `_plugin_api_mount_skip_reason`: the profile's own config
 * does not list the plugin; the plugin's `native_policy` answers 403 "Plugin not authorized" on `/mobile/gateway-ticket`). Only existing app API is used, so this
 * runs on the base too: the refusal arrives unchanged, no socket opens, and the text the person sees must be one plain line.
 */
class SecondProfileWireTest {
    private val line = "Beacon isn't turned on for this profile."

    @Test fun hermesRefusalOfAProfilesPluginIsOnePlainLine() {
        assertEquals(line, appErrorText(ApiFailure(403, "Plugin not authorized", true)))
        assertEquals("Hermes didn't allow that.", appErrorText(ApiFailure(403, "Profile not authorized", true)))
        assertEquals("Finish setup on your Hermes dashboard: open the Beacon tab.", appErrorText(ApiFailure(403, "Owner not authorized", true)))
    }

    @Test(timeout = 15_000) fun aProfileHermesWillNotServeBeaconForOpensNoSocketAndDoesNotFallBack() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val sockets = WebSocket.Factory { _, _ -> error("a socket must not open for a refused profile") }
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null, sockets) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                seen += request
                val refused = request.url.encodedPath == "/api/plugins/beacon/mobile/gateway-ticket"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (refused) 403 else 200).message("Fixture")
                    .body((if (refused) """{"detail":"Plugin not authorized"}""" else """{"ticket":"fixture-ticket"}""").toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val failure = runCatching { client.nativeRpc("status") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ApiFailure && failure.status == 403 && failure.plugin)
            // The one request is the plugin's ticket for exactly this profile; the stock ticket is not tried (it would open a socket Hermes does not serve for Beacon).
            assertEquals(listOf("/api/plugins/beacon/mobile/gateway-ticket"), seen.map { it.url.encodedPath })
            assertEquals("work", seen.single().url.queryParameter("beacon_profile"))
            assertNull(seen.single().url.queryParameter("profile"))
            assertEquals(line, appErrorText(failure!!))
        } finally { client.closeNative() }
    }
}
