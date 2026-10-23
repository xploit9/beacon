package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class AuditR8aDataTest {
    @Test fun errorPresentationHidesServerPathsAndParserDetails() {
        val details = listOf(
            "Could not write file: [Errno 28] C:\\Users\\private\\notes.txt",
            "Expecting value: line 1 column 1",
            "Owned native context unavailable",
            "Native lookup unavailable",
            "java.lang.IllegalStateException at /private/profile",
        )
        for (detail in details) {
            val error = ApiFailure(500, detail)
            assertEquals(detail, error.message)
            assertEquals(detail, error.detail)
            assertEquals("Hermes couldn't do that. Try again.", appErrorText(error))
            assertFalse(appErrorText(error).contains(detail))
            assertEquals("Hermes couldn't do that. Try again.", appErrorText(JSONException(detail)))
        }
        assertEquals(APP_CONNECTION_ERROR, appErrorText(IOException("Native request outcome may be unknown")))
        assertEquals("Enter a six-character code.", appErrorText(IllegalArgumentException("Enter a six-character code.")))
        assertEquals("Hermes couldn't do that. Try again.", appErrorText(IllegalArgumentException("Enter C:\\private\\secret")))
    }

    @Test fun errorMappingPreservesProtocolClassificationsAndSafeGuidance() {
        assertTrue(missingNativeConversation(ApiFailure(4007, "session not found")))
        assertFalse(missingNativeConversation(ApiFailure(4007, "another failure")))
        assertEquals("This chat changed. Refresh and try again.", appErrorText(ApiFailure(409, "revision_conflict")))
        assertEquals("Finish setup on your Hermes dashboard: open the Beacon tab.", appErrorText(ApiFailure(403, "Owner not authorized", true)))
        assertTrue(appErrorText(ApiFailure(403, "device_removed", true)).contains("Pair it again"))
        assertEquals("Hermes is busy. Wait a moment and try again.", appErrorText(ApiFailure(429, "private quota state")))
    }

    @Test fun transportFailuresKeepTheirIdentityUntilUiPresentation() = runBlocking {
        val failures = listOf(SocketTimeoutException("read timed out"), NativeAuthException("Refresh is temporarily unavailable"), IOException("connection reset"))
        for (failure in failures) {
            val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
                builder.addInterceptor { throw failure }
            }
            try {
                val returned = runCatching { client.nativeRequest("/api/auth/me") }.exceptionOrNull()
                // Coroutine stack-trace recovery may copy the exception, so compare its type and message.
                assertEquals(failure::class, returned!!::class)
                assertEquals(failure.message, returned.message)
                assertEquals(APP_CONNECTION_ERROR, appErrorText(failure))
            } finally { client.closeNative() }
        }
    }

    @Test fun historyStampIgnoresTheTranscriptAndDetectsRemoteChanges() {
        val original = json("message_count" to 20, "last_active" to 123.5, "title" to "Chat")
        assertEquals(nativeHistoryStamp(original), nativeHistoryStamp(original.copy().put("messages", "large image data")))
        for (field in listOf("message_count", "last_active", "title", "model_config", "archived")) {
            assertNotEquals(field, nativeHistoryStamp(original), nativeHistoryStamp(original.copy().put(field, "changed")))
        }
        assertNull(nativeHistoryStamp(json("message_count" to 20)))
        assertNull(nativeHistoryStamp(json("last_active" to 123.5)))
        assertNull(nativeHistoryStamp(json("message_count" to org.json.JSONObject.NULL, "last_active" to 123.5)))
        assertNull(nativeHistoryStamp(json("message_count" to 20, "last_active" to "invalid")))
    }

    @Test fun workspaceUploadReadsTheFileWhenTheMultipartBodyIsWritten() = runBlocking {
        val file = File.createTempFile("beacon-stream", ".txt")
        file.writeText("before request")
        var observed = false
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/api/files/upload-stream", request.url.encodedPath)
                assertEquals("POST", request.method)
                val body = request.body as MultipartBody
                assertEquals(file.length(), body.parts.last().body.contentLength())
                file.writeText("read from disk during transfer")
                val wire = Buffer().also { body.writeTo(it) }.readUtf8()
                assertTrue(wire.contains("read from disk during transfer"))
                val overwrite = body.parts.single { it.headers?.get("Content-Disposition")?.contains("name=\"overwrite\"") == true }
                assertEquals("false", Buffer().also { overwrite.body.writeTo(it) }.readUtf8())
                assertTrue(wire.contains("/workspace/fixture.txt"))
                assertFalse(wire.contains("data_url"))
                observed = true
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{\"ok\":true}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            assertTrue(client.upload("/api/workspace/upload", file, "fixture.txt", "text/plain", "chat-a", "/workspace").getBoolean("ok"))
            assertTrue(observed)
        } finally { client.closeNative(); file.delete() }
    }

    @Test fun streamingRouteAllowsOnlyUploads() {
        assertTrue(NativeOperationPolicy.direct("/api/files/upload-stream", "POST"))
        for (method in listOf("GET", "DELETE", "PATCH")) assertFalse(NativeOperationPolicy.direct("/api/files/upload-stream", method))
    }

    @Test fun bothTextEditorsUseThePluginSaveWithoutChangingTheirFields() = runBlocking {
        val bodies = mutableListOf<org.json.JSONObject>()
        var previews = 0
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/api/plugins/beacon/mobile/files/text", request.url.encodedPath)
                if (request.method == "POST") bodies += org.json.JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                else { assertEquals("GET", request.method); previews++ }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{\"content\":\"base\\nline\"}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val query = mapOf("path" to "/workspace/fixture.txt", "session_id" to "chat-a")
            val pc = client.get("/api/pc/file", query)
            val workspace = client.get("/api/file", query)
            assertEquals(pc.getString("content"), workspace.getString("content"))
            assertEquals(2, previews)
            val fields = json("path" to "/workspace/fixture.txt", "session_id" to "chat-a", "content" to "one\r\ntwo\nthree", "expected_content" to workspace.getString("content"))
            client.post("/api/pc/file/save", fields.copy())
            client.post("/api/file/save", fields.copy())
            assertEquals(2, bodies.size)
            bodies.forEach { body ->
                assertEquals(fields.getString("path"), body.getString("path"))
                assertEquals(fields.getString("content"), body.getString("content"))
                assertEquals(fields.getString("expected_content"), body.getString("expected_content"))
                assertEquals(fields.keys().asSequence().toSet(), body.keys().asSequence().toSet())
            }
        } finally { client.closeNative() }
    }

    @Test fun longPollTimeoutIsIndependentOfTheGeneralHttpTimeout() = runBlocking {
        var observed = false
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                assertEquals(TimeUnit.MILLISECONDS.toNanos(35_000), chain.call().timeout().timeoutNanos())
                observed = true
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("{\"events\":[],\"next_cursor\":0}".toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            client.nativeGetForProfile("/api/plugins/beacon/mobile/notifications/events", "default", listOf("default"), timeoutMillis = 35_000)
            assertTrue(observed)
            assertEquals(60_000, client.http.readTimeoutMillis)
        } finally { client.closeNative() }
    }

    @Test fun missingPluginUsesNativeTextEditingButInvalidUtf8CannotBeSaved() = runBlocking {
        var fileText = "before\r\n"
        var writes = 0
        var reads = 0
        var pluginStatus = 404
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val plugin = request.url.encodedPath == "/api/plugins/beacon/mobile/files/text"
                val body = when (request.url.encodedPath) {
                    "/api/plugins/beacon/mobile/files/text" -> json("detail" to "Not Found")
                    "/api/fs/read-text" -> { reads++; json("text" to fileText, "path" to "/workspace/fixture.txt", "binary" to false, "truncated" to false) }
                    "/api/fs/write-text" -> {
                        val fields = org.json.JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                        writes++
                        fileText = fields.getString("content").replace("\n", "\r\n")
                        json("ok" to true)
                    }
                    else -> error("Unexpected fixture route")
                }
                if (!plugin) assertEquals("default", request.url.queryParameter("profile"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (plugin) pluginStatus else 200).message("Fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val query = mapOf("path" to "/workspace/fixture.txt", "session_id" to "chat-a")
        try {
            for ((read, save) in listOf("/api/file" to "/api/file/save", "/api/pc/file" to "/api/pc/file/save")) {
                fileText = "before\r\n"
                val preview = client.get(read, query)
                val saved = client.post(save, json("path" to query.getValue("path"), "session_id" to "chat-a", "content" to "after\r\n", "expected_content" to preview.getString("content")))
                assertEquals("after\r\n", saved.getString("content"))
            }
            assertEquals(2, writes)
            fileText = "invalid\uFFFDtext"
            val preview = client.get("/api/file", query)
            assertEquals("This file can't be edited here.", preview.getString("edit_blocked_reason"))
            val failure = runCatching { client.post("/api/file/save", json("path" to query.getValue("path"), "session_id" to "chat-a", "content" to "replacement", "expected_content" to fileText)) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("This file can't be edited here.", appErrorText(failure!!))
            assertEquals(2, writes)
            val readsBeforeDenial = reads
            pluginStatus = 403
            assertTrue(runCatching { client.get("/api/file", query) }.exceptionOrNull() is ApiFailure)
            assertEquals(readsBeforeDenial, reads)
        } finally { client.closeNative() }
    }
}
