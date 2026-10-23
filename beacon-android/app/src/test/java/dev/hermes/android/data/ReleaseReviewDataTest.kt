package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ReleaseReviewDataTest {
    @Test fun offlineConversationActionsKeepTheOriginalNetworkFailure() {
        val offline = IOException("Synthetic offline")
        for (path in listOf("/api/session/pin", "/api/session/archive", "/api/session/rename", "/api/session/branch", "/api/approval/mode", "/api/sessions")) {
            assertSame(offline, conversationActionFailure(path, offline))
            val api = conversationActionFailure(path, ApiFailure(403, "Private server detail"))
            assertTrue(api is ApiFailure)
            assertFalse(api.message.orEmpty().contains("Private server detail"))
        }
    }

    @Test fun plugin401AllowsNativeFilePreviewAndRevisionCheckedSave() = runBlocking {
        var fileText = "before"
        var writes = 0
        val routes = mutableListOf<String>()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                routes += path
                val plugin = path == "/api/plugins/beacon/mobile/files/text"
                val result = when (path) {
                    "/api/plugins/beacon/mobile/files/text" -> json("detail" to "Plugin pairing expired")
                    "/api/fs/read-text" -> json("text" to fileText, "binary" to false, "truncated" to false)
                    "/api/fs/write-text" -> {
                        fileText = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("content")
                        writes++
                        json("ok" to true)
                    }
                    else -> error("Unexpected route: $path")
                }
                if (!plugin) assertEquals("work", request.url.queryParameter("profile"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (plugin) 401 else 200).message("Fixture")
                    .body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val fields = json("session_id" to "chat", "path" to "/workspace/notes.txt")
            val preview = client.get("/api/file", mapOf("session_id" to "chat", "path" to "/workspace/notes.txt"))
            assertEquals("before", preview.getString("content"))
            val saved = client.post("/api/file/save", fields.put("content", "after").put("expected_content", "before"))
            assertEquals("after", saved.getString("content"))
            assertEquals(1, writes)
            val stale = runCatching { client.post("/api/file/save", fields.put("content", "lost edit")) }.exceptionOrNull()
            assertTrue(stale is IllegalArgumentException)
            assertEquals(1, writes)
            assertEquals(1, routes.count { it == "/api/fs/write-text" })
        } finally { client.closeNative() }
    }

    @Test fun htmlUploadRejectionKeepsHttpStatusAndDoesNotLookOffline() = runBlocking {
        val file = File.createTempFile("beacon-upload-", ".txt").apply { writeText("fixture") }
        try {
            for (status in listOf(413, 502, 200)) {
                val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
                    builder.addInterceptor { chain ->
                        assertEquals("/api/files/upload-stream", chain.request().url.encodedPath)
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                            .body("<html>Upload rejected</html>".toResponseBody("text/html".toMediaType())).build()
                    }
                }
                try {
                    val error = runCatching { client.upload("/api/workspace/upload", file, "fixture.txt", "text/plain", "chat", directory = "/workspace") }.exceptionOrNull()
                    if (status == 200) assertTrue(error is IOException && error !is ApiFailure)
                    else { assertTrue(error is ApiFailure); assertEquals(status, (error as ApiFailure).status) }
                } finally { client.closeNative() }
            }
        } finally { file.delete() }
    }

    @Test fun metadataPollRefreshesUntilItHasADisplayedHistoryBaseline() {
        val raw = json("message_count" to 4, "last_active" to 100, "model_config" to json("model" to "chat-model").toString())
        val first = nativeHistoryStamp(raw)
        assertTrue(nativeHistoryNeedsRefresh(null, first))
        assertFalse(nativeHistoryNeedsRefresh(first, nativeHistoryStamp(raw)))
        assertTrue(nativeHistoryNeedsRefresh(first, nativeHistoryStamp(raw.put("message_count", 5))))
        assertTrue(nativeHistoryNeedsRefresh(first, null))
    }
}
