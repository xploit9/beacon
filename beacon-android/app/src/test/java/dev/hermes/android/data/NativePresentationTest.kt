package dev.hermes.android.data

import dev.hermes.android.Message
import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NativePresentationTest {
    @Test fun profileMismatchAndUnreadableNewFileAreRejectedBeforeNetworkWrites() = runBlocking<Unit> {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", profile = "work"), null) { builder ->
            builder.addInterceptor { error("Invalid operation must not reach the host") }
        }
        for (path in listOf("/api/crons", "/api/cron/jobs", "/api/tools/toolsets")) {
            assertTrue(runCatching { client.get(path, mapOf("profile" to "other")) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (text in listOf("x".repeat(512 * 1024 + 1), "\n".repeat(256 * 1024 + 1), "binary\u0000")) {
            assertTrue(runCatching { client.post("/api/file/save", json("session_id" to "stored", "path" to "C:/work/notes.txt", "content" to text, "expected_content" to "old")) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun retiredServerDictationCannotReachAnOldService() = runBlocking<Unit> {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { error("Capability discovery must not call a retired service") }
        }
        val failure = runCatching { client.get("/api/transcribe/capability") }.exceptionOrNull()
        assertTrue(failure is ApiFailure && failure.status == 501)
    }
    @Test fun serverDisplayTextPreservesRawSourceAndLiteralText() {
        val row = json("role" to "user", "content" to "native raw wrapper", "display_content" to "Literal <memory>chosen by owner</memory>")
        assertEquals("Literal <memory>chosen by owner</memory>", Message.from(row, 0).text)
        assertEquals("native raw wrapper", row.getString("content"))
        assertEquals("Literal <memory>unchanged</memory>", Message.from(json("role" to "user", "content" to "Literal <memory>unchanged</memory>"), 0).text)
    }
    @Test fun nativeFilePreviewAndStaleEditCheckUseRealFsRoutes() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val c = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val req = chain.request(); requests.add(req.url.encodedPath)
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("""{"text":"new remote text","truncated":true,"path":"C:/workspace/file.txt"}""".toResponseBody("application/json".toMediaType())).build()
            }
        }
        val preview = c.get("/api/file", mapOf("session_id" to "stored", "path" to "C:/workspace/file.txt"))
        assertEquals("new remote text", preview.getString("content"))
        assertTrue(preview.getString("edit_blocked_reason").isNotBlank())
        assertTrue(runCatching { c.post("/api/file/save", json("session_id" to "stored", "path" to "C:/workspace/file.txt", "content" to "local draft", "expected_content" to "old remote text")) }.isFailure)
        assertEquals(listOf("/api/fs/read-text", "/api/fs/read-text"), requests)
    }
    @Test fun scheduleOutputDoesNotInventAnEmptyNativeResult() = runBlocking<Unit> {
        val c = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"))
        val error = runCatching { c.get("/api/crons/output", mapOf("job_id" to "fixture-job")) }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 501)
    }

}
