package dev.hermes.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSkillsTest {
    @Test fun transientReadsRecoverButWritesAndAuthFailuresAreNotRetried() = runBlocking<Unit> {
        var attempts = 0
        var status = 502
        val client = CypherClient(AuthContext("https://native.example", emptyList(), profile = "work"), null) { builder ->
            builder.addInterceptor { chain ->
                attempts++
                val code = if (chain.request().method == "GET" && status == 502 && attempts > 1) 200 else status
                val body = if (code == 200) """{"name":"mail","content":"# Mail","revision":"${"a".repeat(64)}"}""" else ""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Result").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val skills = NativeSkills(client)
        assertEquals("# Mail", skills.read("mail").content)
        assertEquals(2, attempts)
        attempts = 0
        assertTrue(runCatching { skills.save("mail", "# Updated", "a".repeat(64)) }.exceptionOrNull() is ApiFailure)
        assertEquals(1, attempts)
        attempts = 0; status = 401
        assertEquals(401, (runCatching { skills.read("mail") }.exceptionOrNull() as ApiFailure).status)
        assertEquals(1, attempts)
        assertTrue(nativeErrorMessage(JSONObject(), 502).contains("Retry"))
    }

    @Test fun nativeRowsPreserveDisabledStateAndRejectUnknownState() {
        val rows = parseNativeSkills(JSONObject("""{"items":[{"name":"mail","description":null,"category":"work","enabled":false,"provenance":"hub","requirements":[],"essential":false}]}"""))
        assertFalse(rows.single().enabled)
        assertEquals("", rows.single().description)
        assertTrue(runCatching { parseNativeSkills(JSONObject("""{"items":[{"name":"mail"}]}""")) }.isFailure)
        assertTrue(runCatching { parseNativeSkills(JSONObject("""{"items":[{"name":"mail","enabled":"true"}]}""")) }.isFailure)
    }

    @Test fun nativeReadAndWritesUseBoundProfileAndRealBodyShapes() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        var enabled = false
        val client = CypherClient(AuthContext("https://native.example", emptyList(), profile = "work"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                val payload = when {
                    request.url.encodedPath == "/api/plugins/cypher/mobile/skills" -> """{"items":[{"name":"mail","description":"Read mail","category":"work","enabled":$enabled,"essential":false,"requirements":[]}],"warnings":[]}"""
                    request.method == "GET" -> """{"name":"mail","content":"# Setup\nSign in first.","revision":"${"a".repeat(64)}"}"""
                    request.url.encodedPath.endsWith("toggle") -> { enabled = true; """{"ok":true,"name":"mail","enabled":true}""" }
                    else -> """{"success":true}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val skills = NativeSkills(client)
        assertFalse(skills.list().single().enabled)
        assertTrue(skills.read("mail").content.contains("Sign in first."))
        skills.toggle("mail", true)
        skills.save("mail", "# Setup\nUse the work inbox.", "a".repeat(64))
        assertTrue(requests.take(2).all { it.url.queryParameter("profile") == "work" })
        requests.filter { it.method == "PUT" }.forEach { request ->
            assertEquals("PUT", request.method)
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("work", body.getString("profile")); assertEquals("mail", body.getString("name"))
            if (request.url.encodedPath.endsWith("toggle")) assertTrue(body.getBoolean("enabled"))
            else { assertEquals("# Setup\nUse the work inbox.", body.getString("content")); assertEquals("a".repeat(64), body.getString("revision")) }
        }
        client.bindProfile("other")
        assertTrue(runCatching { skills.list() }.isFailure)
        assertEquals(5, requests.size)
    }
}
