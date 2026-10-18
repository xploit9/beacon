package dev.hermes.android.data

import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAuthProtocolTest {
    private fun fixture(
        owner: String = "native-owner",
        plugin: String = "ok",
        profiles: List<String>? = listOf("default", "work"),
        activeProfile: String = "default",
        identityStatus: Int = 200,
        identityBody: String = """{"user_id":"native-owner","email":"a@example","display_name":"A","provider":"basic"}""",
        identityCookie: String? = null,
        redirectLocation: String? = null,
    ): Pair<CypherClient, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        lateinit var self: CypherClient
        val client = CypherClient(AuthContext("https://native.example", emptyList(), profile = "work"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                fun response(code: Int, body: String, header: Pair<String, String>? = null): Response =
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                        .apply { header?.let { addHeader(it.first, it.second) } }
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                val fixtureResponse = when (request.url.encodedPath) {
                    "/api/auth/me" -> if (redirectLocation != null) response(302, """{"detail":"Redirect"}""", "Location" to redirectLocation)
                        else response(identityStatus, identityBody, identityCookie?.let { "Set-Cookie" to it })
                    "/api/profiles" -> if (profiles == null) response(404, """{"detail":"Not Found"}""")
                        else response(200, JSONObject().put("profiles", JSONArray(profiles.map { json("name" to it) })).toString())
                    "/api/profiles/active" -> response(200, json("active" to activeProfile, "current" to activeProfile).toString())
                    "/api/plugins/cypher/mobile/bootstrap" -> {
                        val profile = request.url.queryParameter("profile").orEmpty()
                        when (plugin) {
                            "absent" -> response(404, """{"detail":"Not Found"}""")
                            "error" -> response(500, """{"detail":"plugin failed"}""")
                            "unauthorized" -> response(401, """{"detail":"Unauthorized"}""")
                            // A projection may describe extension features but never move the native profile.
                            "malformed" -> response(200, json("contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to owner, "profile" to "foreign-profile").toString())
                            "foreign" -> response(200, json("contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to "foreign-owner", "profile" to profile).toString())
                            "unsupported" -> response(200, json("contract_version" to 3, "backend" to "hermes-dashboard", "owner_id" to owner, "profile" to profile).toString())
                            else -> response(200, json(
                                "contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to owner, "profile" to profile,
                                "voice" to json("available" to true, "configured" to true),
                                "capabilities" to json("native_jobs" to true, "pc_recording" to false, "callbacks" to true, "push" to false, "permanent_recording_retention" to false),
                                "release" to json("available" to true, "version_code" to 37L),
                            ).toString())
                        }
                    }
                    else -> error("Unexpected native request: ${request.url.encodedPath}")
                }
                // An application interceptor that answers itself short-circuits OkHttp's BridgeInterceptor, which is
                // what hands Set-Cookie to the CookieJar (`cookieJar.receiveHeaders(url, headers)`). Replay exactly that
                // step with OkHttp's own parser so the production ScopedCookies alone decides what is kept.
                Cookie.parseAll(request.url, fixtureResponse.headers).takeIf { it.isNotEmpty() }?.let { self.jar.saveFromResponse(request.url, it) }
                fixtureResponse
            }
        }
        self = client
        return client to requests
    }

    private fun pluginCalls(requests: List<Request>) = requests.filter { it.url.encodedPath.startsWith("/api/plugins/") }

    @Test fun verifiedIdentityUsesHermesNativeRoutesWithoutPasswordOrPluginDependency() = runBlocking<Unit> {
        val (client, requests) = fixture()
        val boot = client.bootstrap()
        assertEquals("hermes-dashboard", client.auth.backend)
        assertEquals("native-owner", client.auth.owner)
        assertEquals("work", client.auth.profile)
        assertEquals("not_probed", boot.getJSONObject("optional_features").getString("plugin"))
        assertFalse(boot.getJSONObject("voice").getBoolean("available"))
        assertTrue(pluginCalls(requests).isEmpty())

        val optional = client.probeOptionalFeatures()
        assertEquals("available", optional.getString("plugin"))
        assertTrue(optional.getJSONObject("voice").getBoolean("available"))
        assertEquals("work", pluginCalls(requests).single().url.queryParameter("profile"))
        assertTrue(requests.none { it.url.encodedPath in setOf("/api/auth/providers", "/auth/password-login") })
        assertTrue(requests.none { it.header("X-Hermes-CSRF-Token") != null })
    }

    @Test fun bootstrapNeverCallsTheOptionalPlugin() = runBlocking<Unit> {
        val (client, requests) = fixture(plugin = "ok")
        val boot = client.bootstrap()
        assertEquals("hermes-dashboard", client.auth.backend)
        assertTrue(boot.getJSONObject("native").getBoolean("chat"))
        assertEquals("not_probed", boot.getJSONObject("optional_features").getString("plugin"))
        assertFalse(boot.getJSONObject("voice").getBoolean("available"))
        assertTrue(pluginCalls(requests).isEmpty())
        assertTrue(requests.any { it.url.encodedPath == "/api/auth/me" })
        assertTrue(requests.any { it.url.encodedPath == "/api/profiles" })
    }

    @Test fun pluginAbsentStillAuthenticatesNative() = runBlocking<Unit> {
        val (client, requests) = fixture(plugin = "absent")
        val boot = client.bootstrap()
        assertEquals("hermes-dashboard", client.auth.backend)
        assertEquals("native-owner", client.auth.owner)
        assertEquals("work", client.auth.profile)
        assertTrue(pluginCalls(requests).isEmpty())

        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("absent", optional.getString("plugin"))
        assertFalse(optional.getJSONObject("voice").getBoolean("available"))
        assertEquals(before, client.auth)
        assertEquals("work", pluginCalls(requests).single().url.queryParameter("profile"))
        assertEquals("native-owner", boot.getString("owner_id"))
    }

    @Test fun pluginErrorNeverSignsOutOrRebindsOwner() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "error")
        client.bootstrap()
        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("error", optional.getString("plugin"))
        assertEquals(before, client.auth)
        assertEquals("hermes-dashboard", client.auth.backend)
        assertEquals("native-owner", client.auth.owner)
        assertFalse(optional.getJSONObject("voice").getBoolean("available"))
    }

    @Test fun pluginUnauthorizedIsNotAnAuthFailure() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "unauthorized")
        client.bootstrap()
        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("unauthorized", optional.getString("plugin"))
        assertEquals(before, client.auth)
        assertEquals("hermes-dashboard", client.auth.backend)
    }

    @Test fun malformedPluginProjectionIsOptionalFailure() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "malformed")
        client.bootstrap()
        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("malformed", optional.getString("plugin"))
        assertEquals(before, client.auth)
        assertEquals("work", client.auth.profile)
        assertEquals("native-owner", client.auth.owner)
        assertFalse(optional.getJSONObject("voice").getBoolean("available"))
    }

    @Test fun unsupportedPluginContractIsAnOptionalFailure() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "unsupported")
        client.bootstrap()
        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("unsupported", optional.getString("plugin"))
        assertEquals(before, client.auth)
        assertFalse(optional.getJSONObject("voice").getBoolean("available"))
    }

    @Test fun pluginCannotRebindAnotherOwner() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "foreign")
        client.bootstrap()
        val before = client.auth.copy()
        val optional = client.probeOptionalFeatures()
        assertEquals("unauthorized", optional.getString("plugin"))
        assertEquals(before, client.auth)
        assertEquals("native-owner", client.auth.owner)
        assertFalse(optional.has("owner_id"))
        assertFalse(optional.has("profile"))
        assertFalse(optional.has("backend"))
    }

    @Test fun nativeAuthFailurePropagates() = runBlocking<Unit> {
        val (client, requests) = fixture(identityStatus = 401, identityBody = """{"detail":"Unauthorized"}""")
        val error = runCatching { client.bootstrap() }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 401)
        assertEquals("", client.auth.owner)
        assertEquals("", client.auth.backend)
        assertTrue(pluginCalls(requests).isEmpty())
    }

    @Test fun savedProfileStaysScopedToTheNativeCatalog() = runBlocking<Unit> {
        val (work, workRequests) = fixture()
        assertEquals("work", work.bootstrap().getString("profile"))
        work.probeOptionalFeatures()
        assertEquals("work", pluginCalls(workRequests).single().url.queryParameter("profile"))

        val (fallback, fallbackRequests) = fixture(profiles = listOf("default", "ops"), activeProfile = "ops")
        assertEquals("ops", fallback.bootstrap().getString("profile"))
        fallback.probeOptionalFeatures()
        assertEquals("ops", pluginCalls(fallbackRequests).single().url.queryParameter("profile"))

        val (defaultOnly, defaultRequests) = fixture(profiles = listOf("default"), activeProfile = "default")
        assertEquals("default", defaultOnly.bootstrap().getString("profile"))
        assertEquals("default", defaultOnly.auth.profile)
        defaultOnly.probeOptionalFeatures()
        assertEquals("default", pluginCalls(defaultRequests).single().url.queryParameter("profile"))

        val (older, _) = fixture(profiles = null)
        assertEquals("work", older.bootstrap().getString("profile"))
        assertEquals("work", older.auth.profile)
    }

    @Test fun cookieRotationSurvivesOptionalFailure() = runBlocking<Unit> {
        val (client, _) = fixture(plugin = "error", identityCookie = "hermes_session_at=rotated; Path=/; Secure; HttpOnly; Max-Age=3600")
        client.bootstrap()
        val before = client.auth.copy()
        assertTrue(before.cookies.any { it.contains("hermes_session_at=rotated") })
        assertTrue(client.jar.snapshot().any { it.contains("hermes_session_at=rotated") })
        // The jar would send the rotated cookie back to this origin only.
        assertTrue(client.jar.loadForRequest("https://native.example/api/profiles".toHttpUrl()).any { it.name == "hermes_session_at" && it.value == "rotated" })
        assertTrue(client.jar.loadForRequest("https://other.example/api/profiles".toHttpUrl()).isEmpty())
        val optional = client.probeOptionalFeatures()
        assertEquals("error", optional.getString("plugin"))
        assertEquals(before, client.auth)
    }

    @Test fun cookieForAnotherHostIsNeverKeptOrPersisted() = runBlocking<Unit> {
        val (client, _) = fixture(identityCookie = "hermes_session_at=foreign; Domain=other.example; Path=/; Secure; HttpOnly")
        client.bootstrap()
        assertTrue(client.jar.snapshot().none { it.contains("foreign") })
        assertTrue(client.auth.cookies.none { it.contains("foreign") })
    }

    @Test fun redirectIsNeverFollowed() = runBlocking<Unit> {
        val (client, requests) = fixture(redirectLocation = "https://other.example/api/auth/me")
        val error = runCatching { client.bootstrap() }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 302)
        assertEquals(1, requests.size)
        assertEquals("https://native.example/api/auth/me", requests.single().url.toString())
        assertEquals("", client.auth.owner)
    }

    @Test fun probeRequiresVerifiedNativeIdentity() = runBlocking<Unit> {
        val (client, requests) = fixture()
        assertTrue(runCatching { client.probeOptionalFeatures() }.exceptionOrNull() is IllegalStateException)
        assertTrue(requests.isEmpty())
    }

    @Test fun mergeOptionalFeaturesKeepsNativeAuthority() = runBlocking<Unit> {
        val (client, _) = fixture()
        val base = client.bootstrap()
        val optional = client.probeOptionalFeatures()
        val merged = mergeOptionalFeatures(base, optional)
        assertEquals("native-owner", merged.getString("owner_id"))
        assertEquals("work", merged.getString("profile"))
        assertEquals("hermes-dashboard", merged.getString("backend"))
        assertEquals(base.getJSONObject("identity").toString(), merged.getJSONObject("identity").toString())
        assertEquals("available", merged.getJSONObject("optional_features").getString("plugin"))
        assertTrue(merged.getJSONObject("voice").getBoolean("available"))
        assertTrue(merged.getJSONObject("capabilities").getBoolean("native_jobs"))
        assertEquals("not_probed", base.getJSONObject("optional_features").getString("plugin"))
    }

    @Test fun scopedMergeRejectsAProjectionOfAnotherOwnerOrProfile() = runBlocking<Unit> {
        val (client, _) = fixture()
        val base = client.bootstrap()
        val started = client.scope
        assertTrue(optionalFeaturesPending(base, started))
        val optional = client.probeOptionalFeatures()
        val merged = mergeOptionalFeaturesFor(base, started, optional)!!
        assertTrue(merged.getJSONObject("voice").getBoolean("available"))
        assertFalse(optionalFeaturesPending(merged, started))
        // A newer profile switch or sign-in replaced the projection before the probe returned.
        val switched = base.copy().put("profile", "default")
        assertNull(mergeOptionalFeaturesFor(switched, started, optional))
        assertNull(mergeOptionalFeaturesFor(base.copy().put("owner_id", "other-owner"), started, optional))
        assertNull(mergeOptionalFeaturesFor(JSONObject(), started, optional))
        assertFalse(optionalFeaturesPending(switched, started))
        // A transient optional failure may be retried; it never touches native authority.
        val failed = mergeOptionalFeatures(base, json("plugin" to "error", "reason" to "optional_plugin_timeout"))
        assertTrue(optionalFeaturesPending(failed, started))
        assertFalse(failed.getJSONObject("voice").getBoolean("available"))
        assertEquals("native-owner", failed.getString("owner_id"))
    }

    @Test fun profileNamesAreBoundedAndCannotEscape() {
        assertTrue(validNativeProfile("default"))
        assertTrue(validNativeProfile("work-2.a"))
        assertFalse(validNativeProfile(""))
        assertFalse(validNativeProfile("."))
        assertFalse(validNativeProfile(".."))
        assertFalse(validNativeProfile("../other"))
        assertFalse(validNativeProfile("a b"))
    }

    @Test fun unsupportedOperationNeverReachesNetwork() = runBlocking<Unit> {
        val (client, requests) = fixture()
        val error = runCatching { client.post("/api/session/retry") }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 501)
        assertTrue(requests.isEmpty())
    }

    @Test fun nativePluginRequestCannotChangeBoundProfile() = runBlocking<Unit> {
        val (client, requests) = fixture()
        assertTrue(runCatching { client.nativeRequest("/api/plugins/cypher/mobile/calls", "POST", JSONObject().put("profile", "foreign")) }.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test fun legacyOwnerCannotAuthorizeNativePush() {
        val scope = dev.hermes.android.core.Scope("https://native.example", "owner", "work")
        val legacy = AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, "old-csrf")
        assertFalse(legacy.authorizesPushSource(scope))
        assertTrue(legacy.copy(backend = "hermes-dashboard").authorizesPushSource(scope))
        assertFalse(legacy.copy(backend = "hermes-dashboard").authorizesPushSource(scope.copy(owner = "foreign")))
    }

    @Test fun binaryPluginDownloadForcesBoundProfileAndRejectsForeignProfileBeforeNetwork() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val c = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                requests.add(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(byteArrayOf(1, 2, 3).toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }
        val path = "/api/plugins/cypher/mobile/releases/fixture/download"
        assertArrayEquals(byteArrayOf(1, 2, 3), c.bytes(path, mapOf("session_id" to "stored-a")))
        assertEquals("work", requests.single().url.queryParameter("profile"))
        assertNull(requests.single().url.queryParameter("session_id"))
        assertEquals(path, requests.single().url.encodedPath)
        assertTrue(runCatching { c.bytes(path, mapOf("profile" to "foreign")) }.isFailure)
        assertTrue(runCatching { c.bytes(path, data = JSONObject().put("profile", "foreign")) }.isFailure)
        assertEquals(1, requests.size)
        c.bytes("/api/file/raw", mapOf("path" to "C:/workspace/file.txt", "session_id" to "stored-a"))
        assertEquals("/api/fs/download", requests.last().url.encodedPath)
        assertNull(requests.last().url.queryParameter("session_id"))
        assertEquals(2, requests.size)
    }
}
