package dev.hermes.android.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class NativeOperationPolicyTest {
    @Test fun encodedToolsetNamesStillCannotEscapeTheHttpPathBoundary() {
        val client = BeaconClient(AuthContext("https://native.example", emptyList()))
        assertEquals("/api/tools/toolsets/mcp.research", client.url("/api/tools/toolsets/mcp.research").encodedPath)
        for (path in listOf("/api/tools/toolsets/%2e%2e", "/api/tools/toolsets/x%2F..%2Fauth", "/api/tools/toolsets/x%3Fsecret=1", "/api/tools/toolsets/%00")) {
            assertTrue(path, runCatching { client.url(path) }.isFailure)
        }
    }

    @Test fun supportedNativeHistoryAndProfileRoutesAreAllowed() {
        for (path in listOf(
            "/api/sessions",
            "/api/sessions/search",
            "/api/sessions/20261014_125947_0d8269",
            "/api/sessions/20261014_125947_0d8269/messages",
            "/api/sessions/20261014_125947_0d8269/timeline",
            "/api/sessions/20261014_125947_0d8269/latest-descendant",
            "/api/sessions/20261014_125947_0d8269/mutation-snapshot",
            "/api/profiles",
            "/api/profiles/active",
            "/api/auth/me",
            "/api/auth/ws-ticket",
        )) assertTrue(path, NativeOperationPolicy.direct(path, "GET"))

        for (path in listOf(
            "/api/sessions/x/delete",
            "/api/sessions/..",
            "/api/sessions/x/../messages",
            "/api/sessions/x/messages/../../auth/me",
            "/api/sessions/x/latest-descendant/extra",
            "/api/sessions/x/descendants",
            "/api/plugins/beaconx/mobile/bootstrap",
            "/api/mobile/bootstrap",
        )) assertFalse(path, NativeOperationPolicy.direct(path, "GET"))

        val client = BeaconClient(AuthContext("https://native.example", emptyList()))
        assertTrue(runCatching { client.url("/api/sessions/%2e%2e") }.isFailure)
        assertTrue(runCatching { client.url("/api/sessions/x%2F..%2Fauth") }.isFailure)
    }

    @Test fun sessionRoutesAreReadOnlyHttpGet() {
        for (path in listOf(
            "/api/sessions",
            "/api/sessions/search",
            "/api/sessions/session-a",
            "/api/sessions/session-a/messages",
            "/api/sessions/session-a/timeline",
            "/api/sessions/session-a/latest-descendant",
        )) {
            assertTrue("GET $path", NativeOperationPolicy.direct(path, "GET"))
            for (method in listOf("POST", "PUT", "PATCH", "DELETE", "get")) {
                if (method == "PATCH" && path == "/api/sessions/session-a") continue
                assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
            }
        }
        assertTrue("Other allowlisted routes retain their methods", NativeOperationPolicy.direct("/api/model/set", "POST"))
    }

    @Test fun onlyAConversationsOwnSidebarPatchIsAllowed() {
        // Stock Hermes keeps the revision-checked pin fallback.
        assertTrue(NativeOperationPolicy.direct("/api/sessions/session-a", "PATCH"))
        for (path in listOf("/api/sessions", "/api/sessions/search", "/api/sessions/owner-backfill", "/api/sessions/session-a/messages", "/api/sessions/session-a/timeline"))
            assertFalse("PATCH " + path, NativeOperationPolicy.direct(path, "PATCH"))
    }

    @Test fun hermesUpdateStatusReceiptAndProfileModelRoutesAreOpenWithTheirOwnMethodOnly() {
        assertTrue(NativeOperationPolicy.direct("/api/hermes/update", "POST"))
        for (method in listOf("GET", "PUT", "PATCH", "DELETE")) assertFalse("$method /api/hermes/update", NativeOperationPolicy.direct("/api/hermes/update", method))
        for (path in listOf("/api/actions/hermes-update/status", "/api/hermes/update/receipt")) {
            assertTrue("GET $path", NativeOperationPolicy.direct(path, "GET"))
            for (method in listOf("POST", "PUT", "PATCH", "DELETE")) assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
        }
        // Only the update's own action log: no other action name, and no log of the gateway restart or other helpers.
        for (path in listOf("/api/actions/gateway-restart/status", "/api/actions/hermes-update/status/extra", "/api/actions/hermes-update"))
            assertFalse(path, NativeOperationPolicy.direct(path, "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/profiles/work/model", "PUT"))
        for (method in listOf("GET", "POST", "PATCH", "DELETE")) assertFalse("$method model", NativeOperationPolicy.direct("/api/profiles/work/model", method))
        for (path in listOf("/api/profiles/../model", "/api/profiles/Work/model", "/api/profiles/work/model/x", "/api/profiles//model"))
            assertFalse(path, NativeOperationPolicy.direct(path, "PUT"))
        assertTrue(NativeOperationPolicy.direct("/api/model/options", "GET"))
        // A profile's instructions are read as well as written; its description and model are not read here.
        assertTrue(NativeOperationPolicy.direct("/api/profiles/work/soul", "GET"))
        for (path in listOf("/api/profiles/work/description", "/api/profiles/work/model")) assertFalse("GET $path", NativeOperationPolicy.direct(path, "GET"))
    }

    @Test fun clientBoundariesRejectSessionMutationsBeforeTransport() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val client = BeaconClient(AuthContext("https://native.example", emptyList()), null) { builder ->
            builder.addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val session = "/api/sessions/session-a"
            assertTrue(runCatching { client.request(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.nativeRequest(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.nativeGet(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.call(session, method = "DELETE") }.isFailure)
            assertTrue(runCatching { client.copyTo(session, ByteArrayOutputStream(), data = JSONObject()) }.isFailure)
            assertEquals(0, requests.get())
        } finally { client.closeNative() }
    }

    @Test fun oldRoutesCannotEscapeAdapter() {
        assertFalse(NativeOperationPolicy.direct("/api/chat/start", "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/mobile/bootstrap", "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/transcribe", "GET"))
        assertFalse(NativeOperationPolicy.direct("//foreign/api/model/info", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/model/info", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/tools/toolsets/mcp.research", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/beacon/mobile/bootstrap", "GET"))
    }

    @Test fun thePluginIsNeverUpdatedByGitPullFromThePhone() {
        // Every install, update and repair is the pinned install (PluginInstallTest); Hermes' own update route stays closed.
        for (path in listOf("/api/dashboard/agent-plugins/beacon/update", "/api/dashboard/agent-plugins/other/update", "/api/dashboard/agent-plugins/update-all",
                "/api/dashboard/agent-plugins/beacon/disable", "/api/dashboard/agent-plugins/beacon/update/"))
            for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
    }

    @Test fun profilesCanBeListedCreatedDescribedAndDeletedAndNothingElse() {
        assertTrue(NativeOperationPolicy.direct("/api/profiles", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/profiles", "POST"))
        for (method in listOf("PUT", "PATCH", "DELETE", "post")) assertFalse("$method /api/profiles", NativeOperationPolicy.direct("/api/profiles", method))
        // The sticky "active profile" is read-only: choosing a profile goes through the profile switch.
        assertTrue(NativeOperationPolicy.direct("/api/profiles/active", "GET"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) assertFalse("$method /api/profiles/active", NativeOperationPolicy.direct("/api/profiles/active", method))

        for (name in listOf("coder", "my-bot_2", "a", "9lives", "x".repeat(64))) {
            assertTrue("DELETE $name", NativeOperationPolicy.direct("/api/profiles/$name", "DELETE"))
            assertTrue("PUT $name role", NativeOperationPolicy.direct("/api/profiles/$name/description", "PUT"))
            for (method in listOf("GET", "POST", "PUT", "PATCH", "delete")) assertFalse("$method $name", NativeOperationPolicy.direct("/api/profiles/$name", method))
            for (method in listOf("GET", "POST", "PATCH", "DELETE")) assertFalse("$method $name role", NativeOperationPolicy.direct("/api/profiles/$name/description", method))
            // Owner, 4 Nov 2026: a profile's instructions are edited in the app, saved whole through Hermes' own route.
            assertTrue("PUT $name instructions", NativeOperationPolicy.direct("/api/profiles/$name/soul", "PUT"))
            // Lane NR: the same route is read to open a bot (Hermes' GET /api/profiles/{name}/soul), so the plugin keeps no bot-read route.
            assertTrue("GET $name instructions", NativeOperationPolicy.direct("/api/profiles/$name/soul", "GET"))
            for (method in listOf("POST", "PATCH", "DELETE")) assertFalse("$method $name instructions", NativeOperationPolicy.direct("/api/profiles/$name/soul", method))
        }
        // The built-in profile cannot be deleted from here (its role can be set); anything that is not a plain profile id is refused.
        assertFalse(NativeOperationPolicy.direct("/api/profiles/default", "DELETE"))
        assertTrue(NativeOperationPolicy.direct("/api/profiles/default/description", "PUT"))
        for (path in listOf(
            "/api/profiles/", "/api/profiles/Coder", "/api/profiles/-x", "/api/profiles/_x", "/api/profiles/x y", "/api/profiles/..", "/api/profiles/../auth/me",
            "/api/profiles/a/b", "/api/profiles/${"x".repeat(65)}", "/api/profiles/coder/soul/extra", "/api/profiles/coder/description/extra", "/api/profiles/sessions/pull-requests",
        )) for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) assertFalse("$method $path", NativeOperationPolicy.direct(path, method))
    }

    @Test fun deviceRoutesAllowOnlyTheirDeclaredMethods() {
        assertTrue(NativeOperationPolicy.direct("/api/plugins/beacon/devices/link", "POST"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/beacon/devices", "GET"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/beacon/devices/device-123", "DELETE"))

        assertFalse(NativeOperationPolicy.direct("/api/plugins/beacon/devices/link", "GET"))
        assertFalse(NativeOperationPolicy.direct("/api/plugins/beacon/devices", "DELETE"))
        assertFalse(NativeOperationPolicy.direct("/api/plugins/beacon/devices/../mobile/bootstrap", "DELETE"))
    }
}
