package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/**
 * What the app does when Hermes refuses the Beacon plugin for a profile (`web_server_dashboard.py` `_plugin_api_mount_skip_reason`: the profile's own config
 * does not list the plugin; the plugin's `native_policy` answers 403 "Plugin not authorized" on `/mobile/gateway-ticket`). The client asks the plugin to turn
 * Beacon on by itself; when that does not work no socket opens, nothing falls back to another ticket, and the text the person sees is one plain line.
 */
class SecondProfileWireTest {
    private val line = "Beacon isn't turned on for this profile."

    @Test fun hermesRefusalOfAProfilesPluginIsOnePlainLine() {
        assertEquals("Couldn't turn on Beacon for this profile.", appErrorText(ApiFailure(403, "Plugin not authorized", true)))
        assertEquals(line, appErrorText(BeaconOffFailure(null)))
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
                // The profile turned Beacon off on Hermes: the phone's own turn-on leaves it off.
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (refused) 403 else 200).message("Fixture")
                    .body((if (refused) """{"detail":"Plugin not authorized"}""" else """{"ok":true,"profile":"work","enabled":false,"changed":false}""").toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val failure = runCatching { client.nativeRpc("status") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is ApiFailure && failure.status == 403 && failure.plugin)
            // The plugin's ticket for exactly this profile, then the phone's own turn-on for it; the stock ticket is not tried (it would open a socket Hermes does not serve
            // for Beacon), and the ticket is not asked again while the profile stays off.
            assertEquals(listOf("/api/plugins/beacon/mobile/gateway-ticket", "/api/plugins/beacon/admin/profile-plugin/enable"), seen.map { it.url.encodedPath })
            assertTrue(seen.all { it.url.queryParameter("beacon_profile") == "work" && it.url.queryParameter("profile") == null })
            assertEquals(line, appErrorText(failure!!))
        } finally { client.closeNative() }
    }
}
