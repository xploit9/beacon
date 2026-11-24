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
        for (text in listOf("x".repeat(512 * 1024 + 1), "\n".repeat(512 * 1024 + 1), "\r\n".repeat(512 * 1024 + 1), "binary\u0000")) {
            assertTrue(runCatching { client.post("/api/file/save", json("session_id" to "stored", "path" to "C:/work/notes.txt", "content" to text, "expected_content" to "old")) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    private fun saveClient(requests: MutableList<String>, pluginStatus: Int) = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"), null) { builder ->
        builder.addInterceptor { chain ->
            val req = chain.request(); requests.add(req.url.encodedPath)
            val plugin = req.url.encodedPath == "/api/plugins/beacon/mobile/files/text"
            val code = if (plugin) pluginStatus else 200
            val body = if (plugin && pluginStatus == 200) """{"ok":true,"path":"C:/work/notes.txt","byteSize":1}""" else """{"detail":"Not Found","text":"old"}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }
    private fun save(c: BeaconClient, text: String) = runBlocking { c.post("/api/file/save", json("session_id" to "stored", "path" to "C:/work/notes.txt", "content" to text, "expected_content" to "old")) }

    @Test fun anLfTextFileOf480KiBReachesThePluginSave() {
        val requests = mutableListOf<String>()
        val text = "line\n".repeat(480 * 1024 / 5)
        assertTrue(text.toByteArray().size <= 512 * 1024 && text.toByteArray().size + text.count { it == '\n' } > 512 * 1024)
        save(saveClient(requests, 200), text)
        assertEquals(listOf("/api/plugins/beacon/mobile/files/text"), requests)
    }
    @Test fun anOversizedCrlfFileIsRefusedBeforeAnyNetworkWrite() {
        val requests = mutableListOf<String>()
        assertTrue(runCatching { save(saveClient(requests, 200), "line\r\n".repeat(512 * 1024 / 5 + 100)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), requests)
    }
    @Test fun theStockFallbackKeepsTheWorstCaseNewlineGuardWithNoDiskWrite() {
        val requests = mutableListOf<String>()
        assertTrue(runCatching { save(saveClient(requests, 404), "line\n".repeat(480 * 1024 / 5)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(requests.none { it.endsWith("/write-text") })
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
    @Test fun nativeFilePreviewAndStaleEditCheckUsePluginTextRoute() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val c = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val req = chain.request(); requests.add(req.url.encodedPath)
                val status = if (req.method == "POST") 409 else 200
                val body = if (req.method == "POST") """{"detail":"File changed. Reopen and review your draft."}"""
                    else """{"content":"new remote text","text":"new remote text","truncated":true,"edit_blocked_reason":"Download the complete file to edit it.","path":"C:/workspace/file.txt"}"""
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val preview = c.get("/api/file", mapOf("session_id" to "stored", "path" to "C:/workspace/file.txt"))
        assertEquals("new remote text", preview.getString("content"))
        assertTrue(preview.getString("edit_blocked_reason").isNotBlank())
        assertTrue(runCatching { c.post("/api/file/save", json("session_id" to "stored", "path" to "C:/workspace/file.txt", "content" to "local draft", "expected_content" to "old remote text")) }.isFailure)
        assertEquals(listOf("/api/plugins/beacon/mobile/files/text", "/api/plugins/beacon/mobile/files/text"), requests)
    }
    @Test fun scheduleOutputDoesNotInventAnEmptyNativeResult() = runBlocking<Unit> {
        val c = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", backend = "hermes-dashboard"))
        val error = runCatching { c.get("/api/crons/output", mapOf("job_id" to "fixture-job")) }.exceptionOrNull()
        assertTrue(error is ApiFailure && error.status == 501)
    }

}
