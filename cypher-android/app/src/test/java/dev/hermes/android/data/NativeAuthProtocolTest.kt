package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAuthProtocolTest {
    private fun fixture(owner: String = "native-owner"): Pair<CypherClient, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        val client = CypherClient(AuthContext("https://native.example", emptyList(), profile = "work"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                val payload = when(request.url.encodedPath) {
                    "/api/auth/providers" -> """{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}"""
                    "/auth/password-login" -> """{"ok":true}"""
                    "/api/auth/me" -> """{"user_id":"native-owner"}"""
                    "/api/plugins/cypher/mobile/bootstrap" -> """{"contract_version":2,"backend":"hermes-dashboard","owner_id":"$owner","profile":"work"}"""
                    else -> error("Unexpected native request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody("application/json".toMediaType())).build()
            }
        }
        return client to requests
    }
    @Test fun passwordProviderAndVerifiedIdentityAreNative() = runBlocking<Unit> {
        val (client, requests) = fixture()
        client.login("fixture-password", username = "fixture-user")
        assertEquals("hermes-dashboard", client.auth.backend)
        assertEquals("native-owner", client.auth.owner)
        assertEquals("work", requests.last().url.queryParameter("profile"))
        val login = requests.single { it.url.encodedPath == "/auth/password-login" }
        val buffer = okio.Buffer(); login.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        assertEquals("basic", payload.getString("provider"))
        assertEquals("fixture-user", payload.getString("username"))
        assertTrue(requests.none { it.header("X-Hermes-CSRF-Token") != null })
    }
    @Test fun pluginCannotRebindAnotherOwner() = runBlocking<Unit> {
        val (client, _) = fixture("foreign-owner")
        assertTrue(runCatching { client.bootstrap() }.exceptionOrNull() is CachedIdentityMismatch)
        assertEquals("", client.auth.owner)
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
